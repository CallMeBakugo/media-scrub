package app.mediascrub;

import java.io.File;

public final class RunCleaner {
    public static void main(String[] args) throws Exception {
        try {
            boolean result = MediaCleaner.clean(new File(args[0]), new File(args[1]), Boolean.parseBoolean(args[2]));
            System.out.println(result ? "changed" : "clean");
        } catch (MediaCleaner.Unsupported error) {
            System.out.println("skipped: " + error.getMessage());
        }
    }
}
