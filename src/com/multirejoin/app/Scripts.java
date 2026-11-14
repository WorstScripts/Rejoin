package com.multirejoin.app;

import java.util.ArrayList;
import java.util.List;

/** Finds executor folders (Autoexecute / Scripts) and reads/writes script files in them. */
public final class Scripts {
    private Scripts() {}

    public static final class Folder {
        public String path;
        public boolean auto; // true = autoexecute folder, false = scripts folder
        public String label() {
            String p = path.replaceFirst("^/sdcard/", "").replaceFirst("^/storage/emulated/0/", "");
            return (auto ? "[Auto] " : "[Scripts] ") + p;
        }
    }

    /** Looks for folders named like autoexec / autoexecute / auto-exec and scripts, in internal storage. */
    public static List<Folder> find() {
        String cmd = "find /sdcard/ /sdcard/Android/data/ -maxdepth 4 -type d \\( "
                + "-iname 'autoexec*' -o -iname 'auto-exec*' -o -iname 'auto_exec*' -o -iname 'scripts' \\) 2>/dev/null";
        String s = Root.out(cmd);
        List<Folder> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String line : s.split("\n")) {
            String p = line.trim().replaceAll("/+", "/");
            if (!p.startsWith("/") || seen.contains(p)) continue;
            String name = p.substring(p.lastIndexOf('/') + 1).toLowerCase();
            boolean auto = name.startsWith("auto");
            // skip random "scripts" folders that are not inside an executor folder with an autoexec sibling
            if (!auto) {
                String parent = p.substring(0, p.lastIndexOf('/'));
                if (Root.out("ls -d " + Root.q(parent) + "/[Aa][Uu][Tt][Oo]* 2>/dev/null").isEmpty()) continue;
            }
            seen.add(p);
            Folder f = new Folder();
            f.path = p;
            f.auto = auto;
            out.add(f);
        }
        return out;
    }

    public static List<String> files(String folder) {
        List<String> out = new ArrayList<>();
        for (String l : Root.out("ls -1 " + Root.q(folder) + " 2>/dev/null").split("\n")) {
            l = l.trim();
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    public static String read(String folder, String name) {
        return Root.run("cat " + Root.q(folder + "/" + name)).out;
    }

    public static boolean write(String folder, String name, String content) {
        String path = folder + "/" + name;
        try {
            if (!Root.writeAsRoot(path, content.getBytes("UTF-8"))) return false;
        } catch (Exception e) {
            return false;
        }
        // give the file the same owner as its folder so the executor can read it
        Root.run("o=$(stat -c %u:%g " + Root.q(folder) + " 2>/dev/null); [ -n \"$o\" ] && chown $o " + Root.q(path)
                + "; chmod 666 " + Root.q(path) + "; true");
        return true;
    }

    public static boolean delete(String folder, String name) {
        return Root.ok("rm -f " + Root.q(folder + "/" + name));
    }

    /** Copy every file from a Scripts folder into an Autoexecute folder. */
    public static int copyAll(String from, String to) {
        int n = 0;
        for (String f : files(from)) {
            if (Root.ok("[ -f " + Root.q(from + "/" + f) + " ] && cp -f " + Root.q(from + "/" + f) + " " + Root.q(to + "/" + f))) n++;
        }
        Root.run("o=$(stat -c %u:%g " + Root.q(to) + " 2>/dev/null); [ -n \"$o\" ] && chown $o " + Root.q(to) + "/*; true");
        return n;
    }
}
