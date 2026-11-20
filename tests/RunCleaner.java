package app.mediascrub;

import java.io.File;

public final class RunCleaner {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            var fields = new java.util.TreeMap<String, String>();
            MediaCleaner.inspectBlocks(new File(args[0]), fields);
            fields.forEach((name, value) -> System.out.println(name));
            return;
        }
        try {
            boolean result = MediaCleaner.clean(new File(args[0]), new File(args[1]), Boolean.parseBoolean(args[2]));
            System.out.println(result ? "changed" : "clean");
        } catch (MediaCleaner.Unsupported error) {
            System.out.println("skipped: " + error.getMessage());
        }
    }
}
