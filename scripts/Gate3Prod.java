import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Gate 3 : l'image custom sous la CONFIG PROD (PolyglotSandbox) — engine + contexte
 * SandboxPolicy.ISOLATED, cascade sandbox.Max*, pas d'IO.
 *
 * Verifie : [P1] l'option lw-statement-counter est acceptee sous la policy ISOLATED
 * (attribut sandbox=ISOLATED de l'@Option) ; [P2] le binding est publiable/lisible sous
 * ISOLATED ; [P3] comptage deterministe ; [P4] ANTI-TRICHE : le guest ne peut PAS
 * atteindre le compteur (Polyglot.import bloque/absent sous la policy).
 */
public class Gate3Prod {

    static final String LOOP = "var s = 0; for (var i = 0; i < 200000; i++) { s = s + 1; } s";

    static Context newProdContext(Engine engine) {
        return Context.newBuilder("js")
                .engine(engine)
                .sandbox(SandboxPolicy.ISOLATED)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowHostClassLoading(false)
                .out(java.io.OutputStream.nullOutputStream())
                .err(java.io.OutputStream.nullOutputStream())
                .option("sandbox.MaxHeapMemory", "64MB")
                .option("sandbox.MaxStatements", "20000000")
                .option("sandbox.MaxCPUTime", "60s")
                .option("sandbox.MaxCPUTimeCheckInterval", "10ms")
                .option("sandbox.MaxStackFrames", "50000")
                .option("sandbox.MaxThreads", "1")
                .option("sandbox.MaxASTDepth", "5000")
                .option("sandbox.MaxOutputStreamSize", "1MB")
                .option("sandbox.MaxErrorStreamSize", "1MB")
                .allowIO(IOAccess.NONE)
                .build();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Gate 3 : config prod (SandboxPolicy.ISOLATED) ===");

        Engine engine;
        try {
            engine = Engine.newBuilder("js")
                    .sandbox(SandboxPolicy.ISOLATED)
                    .option("engine.MaxIsolateMemory", "1024MB")
                    .option("lw-statement-counter", "true")
                    .out(java.io.OutputStream.nullOutputStream())
                    .err(java.io.OutputStream.nullOutputStream())
                    .build();
            System.out.println("[P1] OK: engine ISOLATED + option lw-statement-counter acceptee");
        } catch (IllegalArgumentException e) {
            System.out.println("FAIL [P1]: option refusee sous ISOLATED: " + e.getMessage());
            return;
        }

        try (engine) {
            long run1, run2;
            try (Context ctx = newProdContext(engine)) {
                Value counter = ctx.getPolyglotBindings().getMember("lwStatementCounter");
                if (counter == null || !counter.canExecute()) {
                    System.out.println("FAIL [P2]: binding absent sous ISOLATED (publish instrument bloque ?)");
                    return;
                }
                System.out.println("[P2] OK: binding lisible cote hote sous ISOLATED");

                counter.execute(0);
                ctx.eval(Source.create("js", LOOP));
                run1 = counter.execute().asLong();

                // [P4] anti-triche : le guest tente d'atteindre le compteur
                String cheat = "(function(){"
                        + "if (typeof Polyglot === 'undefined') return 'NO_POLYGLOT_BUILTIN';"
                        + "try { var c = Polyglot.import('lwStatementCounter');"
                        + "      if (c == null) return 'IMPORT_NULL';"
                        + "      c(0); return 'CHEAT_RESET_OK'; }"
                        + "catch (e) { return 'IMPORT_BLOCKED: ' + e; } })()";
                String verdict;
                try {
                    verdict = ctx.eval(Source.create("js", cheat)).asString();
                } catch (PolyglotException e) {
                    verdict = "EVAL_THROWN: " + e.getMessage();
                }
                long afterCheat = counter.execute().asLong();
                boolean safe = !verdict.equals("CHEAT_RESET_OK") && afterCheat >= run1;
                System.out.println("[P4] " + (safe ? "OK" : "FAIL") + ": tentative guest = " + verdict
                        + " (compteur " + run1 + " -> " + afterCheat + ")");
            }
            try (Context ctx = newProdContext(engine)) {
                Value counter = ctx.getPolyglotBindings().getMember("lwStatementCounter");
                counter.execute(0);
                ctx.eval(Source.create("js", LOOP));
                run2 = counter.execute().asLong();
            }
            System.out.println("[P3] run1=" + run1 + " run2=" + run2
                    + (run1 == run2 && run1 > 0 ? " -> DETERMINISTE" : " -> KO"));
        }
        System.out.println("=== fin Gate 3 ===");
    }
}
