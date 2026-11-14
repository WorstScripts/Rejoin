package com.multirejoin.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Runs shell commands as root through "su". */
public final class Root {
    private Root() {}

    private static volatile String suPath = null;
    public static volatile String lastError = "";
    private static final String[] SU_PATHS = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su"};

    /** Finds a working su binary (Magisk, KernelSU, APatch, cloud phones). */
    public static synchronized String su() {
        if (suPath != null) return suPath;
        StringBuilder errs = new StringBuilder();
        for (String c : SU_PATHS) {
            Process p = null;
            try {
                p = new ProcessBuilder(c, "-c", "id").redirectErrorStream(true).start();
                p.getOutputStream().close();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                InputStream is = p.getInputStream();
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
                p.waitFor();
                String o = bo.toString("UTF-8");
                if (o.contains("uid=0")) { suPath = c; lastError = ""; return c; }
                errs.append(c).append(": ").append(o.trim()).append("\n");
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (!m.contains("error=2,")) errs.append(c).append(": ").append(m).append("\n");
            } finally {
                if (p != null) try { p.destroy(); } catch (Exception ignored) {}
            }
        }
        lastError = errs.length() == 0 ? "No su found. This device is not rooted." : errs.toString().trim();
        return null;
    }

    public static final class Res {
        public final int code;
        public final String out;
        Res(int code, String out) { this.code = code; this.out = out; }
        public boolean ok() { return code == 0; }
    }

    public static Res run(String cmd) {
        Process p = null;
        try {
            String su = su();
            if (su == null) return new Res(-1, "");
            ProcessBuilder pb = new ProcessBuilder(su);
            pb.redirectErrorStream(true);
            p = pb.start();
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\nexit $?\n").getBytes("UTF-8"));
            os.flush();
            os.close();
            InputStream is = p.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            int code = p.waitFor();
            return new Res(code, bo.toString("UTF-8").replace("\r", ""));
        } catch (Exception e) {
            return new Res(-1, "");
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    public static String out(String cmd) { return run(cmd).out.trim(); }

    /** Copy a file only root can read into a file we own (data is streamed through "cat"). */
    public static boolean catTo(String path, java.io.File out) {
        Process p = null;
        try {
            p = new ProcessBuilder(su() == null ? "su" : su(), "-c", "cat " + q(path) + " 2>/dev/null").start();
            p.getOutputStream().close();
            InputStream is = p.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) { fo.write(buf, 0, n); total += n; }
            fo.close();
            return p.waitFor() == 0 && total > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /** Write bytes to a path as root (data is streamed through "cat"). */
    public static boolean writeAsRoot(String path, byte[] data) {
        Process p = null;
        try {
            p = new ProcessBuilder(su() == null ? "su" : su(), "-c", "cat > " + q(path)).start();
            OutputStream os = p.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    public static boolean ok(String cmd) { return run(cmd).ok(); }

    public static boolean available() { return su() != null; }

    /** Allow a retry after the user grants root. */
    public static synchronized void reset() { suPath = null; }

    /** Single-quote a string for the shell. */
    public static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
