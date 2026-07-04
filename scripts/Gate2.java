import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

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
        System.out.println("=== Gate 2 : lecture hote du StatementCounter sous isolate (via bindings) ===");

        try (Engine engine = newIsolateEngine(true)) {
            System.out.println("[1] instruments = " + engine.getInstruments().keySet());
            Instrument instr = engine.getInstruments().get(StatementCounter.ID);
            if (instr == null) { System.out.println("FAIL [1]: instrument absent"); return; }
            // informatif : le lookup service ne marche pas sous isolate
            System.out.println("[1b] lookup service (attendu null sous isolate) = "
                    + instr.lookup(StatementCounter.Counter.class));

            long run1, run2;
            try (Context ctx = Context.newBuilder("js").engine(engine).build()) {
                Value counter = ctx.getPolyglotBindings().getMember(StatementCounter.BINDING_NAME);
                if (counter == null) { System.out.println("FAIL [2]: binding absent"); return; }
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
            System.out.println("[3] run1=" + run1 + " run2=" + run2
                    + (run1 == run2 && run1 > 0 ? " -> DETERMINISTE" : " -> KO"));

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
    }
}
