import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import com.leekwars.generator.polyglot.StatementCounter;

/**
 * Gate 2 du spike custom isolate : l'hote peut-il LIRE le compteur d'un instrument
 * embarque dans l'image isolate, et a quel cout ?
 *
 * Le lookup de service ne traverse pas la frontiere (verifie null) ; la lecture passe par
 * les POLYGLOT BINDINGS : l'instrument publie un executable "lwStatementCounter"
 * (execute() = lire, execute(x) = reset) dans chaque contexte.
 */
public class Gate2 {

    static final String LOOP = "var s = 0; for (var i = 0; i < 200000; i++) { s = s + 1; } s";

    static Engine newIsolateEngine(boolean withCounter) {
        Engine.Builder b = Engine.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("engine.SpawnIsolate", "true")
                .option("engine.MaxIsolateMemory", "1024MB");
        if (withCounter) {
            b.option("lw-statement-counter", "true");
        }
        return b.build();
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        System.out.println("=== Gate 2 : lecture hote du StatementCounter sous isolate (via bindings) ===");

        try (Engine engine = newIsolateEngine(true)) {
            System.out.println("[1] instruments = " + engine.getInstruments().keySet());
            Instrument instr = engine.getInstruments().get(StatementCounter.ID);
            if (instr == null) { System.out.println("FAIL [1]: instrument absent"); System.exit(1); }

            long run1, run2;
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                if (counter == null) { System.out.println("FAIL [2]: binding absent"); System.exit(1); }
                System.out.println("[2] OK: binding present, executable=" + counter.canExecute());
                counter.execute("reset");
                ctx.eval(Source.create("js", LOOP));
                run1 = counter.execute().asLong();
            }
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                counter.execute("reset");
                ctx.eval(Source.create("js", LOOP));
                run2 = counter.execute().asLong();
            }
            boolean deterministic = run1 == run2 && run1 > 0;
            System.out.println("[3] run1=" + run1 + " run2=" + run2 + (deterministic ? " -> DETERMINISTE" : " -> KO"));
            if (!deterministic) failures++;

            // [3b] un compteur par contexte : l'engine est partage par des contextes qui tournent en
            // parallele (un worker joue plusieurs combats a la fois). Chacun doit compter run1, ni
            // plus (statements des autres) ni moins (remise a zero par un autre).
            try (Context a = Context.newBuilder("js").engine(engine).build();
                 Context b = Context.newBuilder("js").engine(engine).build()) {
                Value ca = a.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                Value cb = b.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                ca.execute("reset");
                cb.execute("reset");
                b.eval(Source.create("js", LOOP));
                long idle = ca.execute().asLong();
                cb.execute("reset");
                long[] counts = new long[2];
                Thread ta = new Thread(() -> { a.eval(Source.create("js", LOOP)); counts[0] = ca.execute().asLong(); });
                Thread tb = new Thread(() -> { b.eval(Source.create("js", LOOP)); counts[1] = cb.execute().asLong(); });
                ta.start(); tb.start(); ta.join(); tb.join();
                boolean isolated = idle == 0 && counts[0] == run1 && counts[1] == run1;
                System.out.println("[3b] " + (isolated ? "OK" : "KO") + ": contexte inactif=" + idle
                        + " paralleles=" + counts[0] + "/" + counts[1] + " (attendu 0 et " + run1 + ")");
                if (!isolated) failures++;
            }

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

        // [5b] baseline : engine isolate SANS l'option -> instrument inactif
        try (Engine engine = newIsolateEngine(false);
             Context ctx = Context.newBuilder("js").engine(engine).build()) {
            ctx.eval(Source.create("js", LOOP)); // warmup
            long t1 = System.nanoTime();
            for (int i = 0; i < 20; i++) ctx.eval(Source.create("js", LOOP));
            System.out.println("[5b] sans instrument (option non posee): "
                    + ((System.nanoTime() - t1) / 20 / 1000) + " us/eval");
        }

        System.out.println("=== fin Gate 2 ===");
        // Code de sortie = verdict.
        if (failures > 0) System.exit(1);
    }
}
