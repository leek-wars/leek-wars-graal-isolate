import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import com.leekwars.generator.polyglot.StatementCounter;

/**
 * Gate 2 du spike custom isolate : l'hote peut-il LIRE le compteur d'un instrument
 * embarque dans l'image isolate, et a quel cout ?
 *
 * Un service d'instrument ne traverse pas la frontiere ; la lecture passe par
 * les POLYGLOT BINDINGS : l'instrument publie un executable "lwStatementCounter"
 * (execute() = lire, execute(x) = reset) dans chaque contexte.
 */
public class Gate2 {

    static final String LOOP = "var s = 0; for (var i = 0; i < 200000; i++) { s = s + 1; } s";

    static final String PY_LOOP = "s = 0\nfor i in range(200000):\n    s = s + 1\n";

    static long count(Context ctx, Value counter, String lang, String code) {
        counter.execute("reset");
        ctx.eval(Source.create(lang, code));
        return counter.execute().asLong();
    }

    /**
     * [3b] un compteur par contexte : l'engine est partage par des contextes qui tournent en parallele
     * (un worker joue plusieurs combats a la fois). Un contexte inactif doit lire 0, et deux contextes
     * en parallele doivent compter chacun comme un contexte seul : ni plus (statements de l'autre) ni
     * moins (remise a zero par l'autre).
     */
    static boolean countsPerContext(Engine engine, String lang, String code) throws InterruptedException {
        try (Context a = Context.newBuilder(lang).engine(engine).build();
             Context b = Context.newBuilder(lang).engine(engine).build()) {
            Value ca = a.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
            Value cb = b.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
            // Echauffement : le 1er eval d'un contexte Python compte en plus son initialisation paresseuse.
            a.eval(Source.create(lang, code));
            b.eval(Source.create(lang, code));
            ca.execute("reset");
            long alone = count(b, cb, lang, code);
            long idle = ca.execute().asLong();
            long[] counts = new long[2];
            Thread ta = new Thread(() -> counts[0] = count(a, ca, lang, code));
            Thread tb = new Thread(() -> counts[1] = count(b, cb, lang, code));
            ta.start(); tb.start(); ta.join(); tb.join();
            boolean isolated = alone > 0 && idle == 0 && counts[0] == alone && counts[1] == alone;
            System.out.println("[3b " + lang + "] " + (isolated ? "OK" : "KO") + ": contexte inactif=" + idle
                    + " paralleles=" + counts[0] + "/" + counts[1] + " (attendu 0 et " + alone + ")");
            return isolated;
        }
    }

    /**
     * [7] un seul compteur : les elements que GraalPy parcourt ou trie en Java s'ajoutent au compteur de
     * l'instrument qu'on lui confie (__graalpython__.lw_set_operations), un par niveau d'iterateur natif,
     * rien de plus pour une boucle deja comptee ; au-dela de la limite, statements comme natif levent
     * l'erreur de l'instrument.
     */
    static boolean nativeOperations(Engine engine) {
        boolean ok = true;
        try (Context ctx = Context.newBuilder("python").engine(engine).build()) {
            Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
            Value set = ctx.eval("python", "__import__('__graalpython__').lw_set_operations");
            ctx.eval("python", "l = list(range(1000)); d = dict.fromkeys(l)");
            ok &= check("compteur accepte", set.execute(counter).asBoolean(), true);
            ok &= check("natif de sum(map(abs, l))", nativePart(ctx, counter, set, "sum(map(abs, l))"), 2000L);
            ok &= check("natif de l.sort()", nativePart(ctx, counter, set, "l.sort()"), 1000L);
            ok &= check("natif d'un for sur un dict", nativePart(ctx, counter, set, "[x for x in d]"), 0L);
            ctx.eval("python", "l2 = list(range(1000)); s = 'a' * 1000");
            ok &= check("natif de 999 in l", nativePart(ctx, counter, set, "999 in l"), 1000L);
            ok &= check("natif de l == l2", nativePart(ctx, counter, set, "l == l2"), 1000L);
            ok &= check("natif de s.find('b')", nativePart(ctx, counter, set, "s.find('b')"), 101L);
            for (String code : new String[] { "sum(map(abs, l * 100))", "while True: pass", "try:\n    while True: pass\nexcept BaseException:\n    while True: pass" }) {
                ok &= check("limite : " + code.replace("\n", " "), limitReached(ctx, counter, "python", code, 0), true);
            }
            // Les operations de l'hote reduisent la limite : 4990 sur 5000, une petite boucle la depasse
            ok &= check("limite apres l'hote", limitReached(ctx, counter, "python", "for i in range(100): pass", 4990), true);
            // Le lecteur du guest rend le total (hote + compte) sans rien pouvoir changer
            Value reader = ctx.getPolyglotBindings().getMember(StatementCounter.READER_BINDING_NAME);
            counter.execute("turn", 1000, 1.0, 7);
            ctx.eval("python", "x = 1");
            long total = reader.execute().asLong();
            reader.execute("turn", Long.MAX_VALUE, 1.0, 0);
            reader.execute("reset");
            ok &= check("lecteur en lecture seule", total > 7 && reader.execute().asLong() == total && counter.execute().asLong() == total - 7, true);
        }
        return ok;
    }

    /** Vrai si code s'arrete sur l'erreur de l'instrument, budget de 5000 operations dont host deja faites par l'hote. */
    static boolean limitReached(Context ctx, Value counter, String lang, String code, long host) {
        counter.execute("turn", 5000, 1.0, host);
        try {
            ctx.eval(lang, code);
            return false;
        } catch (PolyglotException e) {
            return String.valueOf(e.getMessage()).contains(StatementCounter.LIMIT_MESSAGE);
        }
    }

    /** Operations natives de code : compte avec le compteur confie a GraalPy, moins sans. */
    static long nativePart(Context ctx, Value counter, Value set, String code) {
        set.execute(counter);
        long with = countPython(ctx, counter, code);
        set.execute((Object) null);
        long without = countPython(ctx, counter, code);
        set.execute(counter);
        return with - without;
    }

    /** Operations comptees pendant code Python (compteur remis a zero, sans limite). */
    static long countPython(Context ctx, Value counter, String code) {
        counter.execute("turn", Long.MAX_VALUE, 1.0, 0);
        ctx.eval("python", code);
        return counter.execute().asLong();
    }

    static boolean check(String what, Object value, Object expected) {
        boolean ok = String.valueOf(value).equals(String.valueOf(expected));
        System.out.println("[7] " + (ok ? "OK" : "KO") + ": " + what + " -> " + value + (ok ? "" : " (attendu " + expected + ")"));
        return ok;
    }

    static Engine newIsolateEngine(String lang, boolean withCounter) {
        Engine.Builder b = Engine.newBuilder(lang)
                .allowExperimentalOptions(true)
                .option("engine.SpawnIsolate", "true")
                .option("engine.MaxIsolateMemory", "1024MB");
        if (withCounter) {
            b.option("lw-statement-counter", "true");
        }
        return b.build();
    }

    /**
     * [7] plafonds des patches graaljs/graalpython : le calcul trop gros est refuse (exception dont le
     * message contient {@code expected}) ou rendu sans travail quadratique (resultat egal a
     * {@code expected}), en quelques millisecondes.
     */
    static boolean fast(Engine engine, String lang, String code, String expected, boolean refused) {
        try (Context ctx = Context.newBuilder(lang).engine(engine).build()) {
            long t = System.nanoTime();
            String got;
            boolean threw = false;
            try {
                got = ctx.eval(lang, code).toString();
            } catch (PolyglotException e) {
                got = String.valueOf(e.getMessage());
                threw = true;
            }
            long ms = (System.nanoTime() - t) / 1_000_000;
            boolean ok = threw == refused && (refused ? got.contains(expected) : got.equals(expected)) && ms < 2_000;
            System.out.println("[7 " + lang + "] " + (ok ? "OK" : "KO") + ": " + code + " -> " + got + " (" + ms + " ms)");
            return ok;
        }
    }

    static boolean refusedFast(Engine engine, String lang, String code, String message) {
        return fast(engine, lang, code, message, true);
    }

    static boolean returnsFast(Engine engine, String lang, String code, String result) {
        return fast(engine, lang, code, result, false);
    }

    /**
     * [8] boucle native sur une taille virtuelle : l'interruption du contexte doit y atterrir. Le
     * calcul doit encore tourner a l'interruption, et finir par elle, pas par une autre erreur.
     */
    static boolean interruptible(Engine engine, String lang, String code) throws Exception {
        try (Context ctx = Context.newBuilder(lang).engine(engine).build()) {
            ctx.initialize(lang);
            AtomicReference<String> end = new AtomicReference<>("en cours");
            Thread t = new Thread(() -> {
                try {
                    ctx.eval(lang, code);
                    end.set("fini sans interruption");
                } catch (PolyglotException e) {
                    end.set(e.isInterrupted() || e.isCancelled() ? "interrompu" : "erreur : " + e.getMessage());
                }
            });
            t.setDaemon(true);
            long start = System.nanoTime();
            t.start();
            Thread.sleep(300);
            boolean runningAtInterrupt = t.isAlive();
            boolean ok;
            try {
                ctx.interrupt(Duration.ofSeconds(5));
                t.join(5_000);
                ok = runningAtInterrupt && !t.isAlive() && "interrompu".equals(end.get());
            } catch (TimeoutException e) {
                ok = false;
            }
            System.out.println("[8 " + lang + "] " + (ok ? "OK" : "KO") + ": " + code + " -> " + end.get() + " en "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            if (!ok) {
                ctx.close(true);
            }
            return ok;
        }
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        System.out.println("=== Gate 2 : lecture hote du StatementCounter sous isolate (via bindings) ===");

        try (Engine engine = newIsolateEngine("js", true)) {
            System.out.println("[1] instruments = " + engine.getInstruments().keySet());
            Instrument instr = engine.getInstruments().get(StatementCounter.ID);
            if (instr == null) { System.out.println("FAIL [1]: instrument absent"); System.exit(1); }

            long run1, run2;
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                if (counter == null) { System.out.println("FAIL [2]: binding absent"); System.exit(1); }
                System.out.println("[2] OK: binding present, executable=" + counter.canExecute());
                run1 = count(ctx, counter, "js", LOOP);
            }
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                run2 = count(ctx, ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME), "js", LOOP);
            }
            boolean deterministic = run1 == run2 && run1 > 0;
            System.out.println("[3] run1=" + run1 + " run2=" + run2 + (deterministic ? " -> DETERMINISTE" : " -> KO"));
            if (!deterministic) failures++;

            if (!countsPerContext(engine, "js", LOOP)) failures++;

            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                // [4] cout d'une lecture a travers la frontiere
                counter.execute();
                long t0 = System.nanoTime();
                long sink = 0;
                for (int i = 0; i < 10_000; i++) sink += counter.execute().asLong();
                long perRead = (System.nanoTime() - t0) / 10_000;
                System.out.println("[4] cout lecture compteur ~" + perRead + " ns/lecture (sink=" + sink + ")");

                // [5] overhead du comptage (instrument actif car binding lu)
                ctx.eval(Source.create("js", LOOP)); // warmup
                long t1 = System.nanoTime();
                for (int i = 0; i < 20; i++) ctx.eval(Source.create("js", LOOP));
                System.out.println("[5] avec instrument actif: " + ((System.nanoTime() - t1) / 20 / 1000) + " us/eval");
            }

            // [6] memoire retenue par le contexte (System.usedRAM) : lue par l'hote, puis depuis un
            // appel guest -> hote (le cas reel : l'IA lit System.usedRAM pendant son tour).
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value heap = ctx.getPolyglotBindings().getMember(StatementCounter.HEAP_BINDING_NAME);
                if (heap == null) { System.out.println("FAIL [6]: binding " + StatementCounter.HEAP_BINDING_NAME + " absent"); System.exit(1); }
                long before = heap.execute().asLong();
                ctx.eval(Source.create("js", "globalThis.keep = []; for (var i = 0; i < 5000000; i++) keep.push(i); keep.length"));
                long t0 = System.nanoTime();
                long after = heap.execute().asLong();
                long us = (System.nanoTime() - t0) / 1000;
                ctx.getBindings("js").putMember("measure", (ProxyExecutable) a -> heap.execute().asLong());
                long nested = ctx.eval(Source.create("js", "measure()")).asLong();
                long stopped = heap.execute(1_000_000L).asLong();
                boolean ok = after - before > 15_000_000 && nested >= after - 1_000_000 && stopped > 1_000_000 && stopped < after;
                if (!ok) failures++;
                System.out.println("[6] " + (ok ? "OK" : "KO") + ": retenu avant=" + before + " apres=" + after + " (" + us
                        + " us) depuis le guest=" + nested + " arrete a 1 Mo=" + stopped);
            }
        }

        // [3b] aussi en Python (tags poses par patches/graalpython.patch)
        try (Engine engine = newIsolateEngine("python", true)) {
            if (!countsPerContext(engine, "python", PY_LOOP)) failures++;
        }

        // [5b] baseline : engine isolate SANS l'option -> instrument inactif
        try (Engine engine = newIsolateEngine("js", false);
             Context ctx = Context.newBuilder("js").engine(engine).build()) {
            ctx.eval(Source.create("js", LOOP)); // warmup
            long t1 = System.nanoTime();
            for (int i = 0; i < 20; i++) ctx.eval(Source.create("js", LOOP));
            System.out.println("[5b] sans instrument (option non posee): "
                    + ((System.nanoTime() - t1) / 20 / 1000) + " us/eval");
        }

        // [7] [8] plafonds des entiers et boucles natives interruptibles (patches Leek Wars)
        try (Engine engine = newIsolateEngine("js", true)) {
            // [7] limite de l'instrument : en JS aussi, la boucle s'arrete, meme attrapee
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                for (String code : new String[] { "for (;;) {}", "try { for (;;) {} } catch (e) { for (;;) {} }" }) {
                    if (!check("js, limite : " + code, limitReached(ctx, counter, "js", code, 0), true)) failures++;
                }
            }
            if (!refusedFast(engine, "js", "3n ** 60000000n", "Maximum BigInt size exceeded")) failures++;
            if (!refusedFast(engine, "js", "1n << 100000000n", "Maximum BigInt size exceeded")) failures++;
            if (!refusedFast(engine, "js", "BigInt('9'.repeat(5000000))", "Maximum BigInt size exceeded")) failures++;
            if (!refusedFast(engine, "js", "eval('x;'.repeat(600000))", "Source too long to evaluate")) failures++;
            if (!refusedFast(engine, "js", "new Function('x;'.repeat(600000))", "Source too long to evaluate")) failures++;
            if (!interruptible(engine, "js", "Array.prototype.indexOf.call({length: 2**53 - 1}, 1)")) failures++;
            if (!interruptible(engine, "js", "Array.prototype.shift.call({length: 2**53 - 1})")) failures++;
            if (!returnsFast(engine, "js", "Number('0x' + 'f'.repeat(1000000))", "Infinity")) failures++;
            if (!returnsFast(engine, "js", "Number('0x' + '0'.repeat(30000) + '1')", "1")) failures++;
            if (!returnsFast(engine, "js", "1n == '9'.repeat(30000)", "false")) failures++;
            if (!returnsFast(engine, "js", "BigInt('0b' + '1'.repeat(30000)).toString(2).length", "30000")) failures++;
            if (!returnsFast(engine, "js", "eval('0b' + '1'.repeat(30000) + 'n').toString(2).length", "30000")) failures++;
        }
        try (Engine engine = newIsolateEngine("python", true)) {
            if (!refusedFast(engine, "python", "3 ** 60000000", "integer too large")) failures++;
            if (!refusedFast(engine, "python", "import math; math.factorial(10 ** 6)", "integer too large")) failures++;
            if (!refusedFast(engine, "python", "pow(7, 3 ** 20000 + 1, 3 ** 20000 + 1)", "integer too large")) failures++;
            if (!refusedFast(engine, "python", "int('f' * 4000000, 16)", "integer too large")) failures++;
            if (!interruptible(engine, "python", "max(iter(range(10 ** 10)))")) failures++;
            if (!interruptible(engine, "python", "sum(range(10 ** 12))")) failures++;
            if (!refusedFast(engine, "python", "exec('x = 0x' + 'f' * 1000000)", "integer literal too large")) failures++;
            // Attentes interdites (LwNoWait) : refusees tout de suite, chemins sans attente inchanges.
            String wait = "waiting is not allowed in Leek Wars";
            if (!refusedFast(engine, "python", "import time; time.sleep(5)", wait)) failures++;
            if (!refusedFast(engine, "python", "import threading; threading.Event().wait(5)", wait)) failures++;
            if (!refusedFast(engine, "python", "import threading; l = threading.Lock(); l.acquire(); l.acquire()", wait)) failures++;
            if (!refusedFast(engine, "python", "import _queue; _queue.SimpleQueue().get(True, 5)", wait)) failures++;
            if (!refusedFast(engine, "python", "import _queue; _queue.SimpleQueue().get()", wait)) failures++;
            if (!refusedFast(engine, "python", "import os; r, w = os.pipe(); os.read(r, 1)", wait)) failures++;
            if (!refusedFast(engine, "python", "import os; r, w = os.pipe(); [os.write(w, b'x' * 100000) for _ in range(20)]", wait)) failures++;
            if (!returnsFast(engine, "python", "(lambda p: (__import__('os').write(p[1], b'xy'), len(__import__('os').read(p[0], 5)))[1])(__import__('os').pipe())", "2")) failures++;
            if (!returnsFast(engine, "python", "(lambda l: (l.acquire(), l.acquire(True, 0))[1])(__import__('threading').Lock())", "false")) failures++;
            if (!returnsFast(engine, "python", "(lambda l: [l.acquire() for _ in range(3)][-1])(__import__('threading').RLock())", "true")) failures++;
            if (!returnsFast(engine, "python", "(lambda q: (q.put(7), q.get(True, 5))[1])(__import__('_queue').SimpleQueue())", "7")) failures++;
            if (!returnsFast(engine, "python", "(lambda e: (e.set(), e.wait(5))[1])(__import__('threading').Event())", "true")) failures++;
            // [7] operations natives (PythonContext.lwCharge) dans le compteur de l'instrument, et sa limite
            if (!nativeOperations(engine)) failures++;
            if (!returnsFast(engine, "python", "hasattr(__import__('__graalpython__'), 'time_millis')", "false")) failures++;
        }

        System.out.println("=== fin Gate 2 ===");
        // Code de sortie = verdict.
        if (failures > 0) System.exit(1);
    }
}
