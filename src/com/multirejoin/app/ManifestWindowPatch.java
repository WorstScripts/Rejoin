package com.multirejoin.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes an app "window-friendly" by editing its binary AndroidManifest.xml, like the "minimized" Roblox mods:
 *  - resizeableActivity=true on the application and every activity
 *  - no forced screen orientation, no aspect-ratio limits
 *  - configChanges covers size changes, so a resize doesn't restart the activity
 *  - a <layout> with a small default window size and small minimum size
 * Works on the whole document (parse → change → write), keeping attributes sorted by resource id.
 */
public final class ManifestWindowPatch {
    private static final int RES_STRING_POOL = 0x0001, RES_XML = 0x0003, RES_RESOURCE_MAP = 0x0180;
    private static final int START_NS = 0x0100, END_NS = 0x0101, START_EL = 0x0102, END_EL = 0x0103, CDATA = 0x0104;
    private static final int UTF8 = 0x100;

    // android:* attribute ids (from AOSP public-final.xml)
    static final int ID_SCREEN_ORIENTATION = 0x0101001e, ID_CONFIG_CHANGES = 0x0101001f, ID_GRAVITY = 0x010100af,
            ID_MIN_WIDTH = 0x0101013f, ID_MIN_HEIGHT = 0x01010140, ID_DEFAULT_WIDTH = 0x010104f4,
            ID_DEFAULT_HEIGHT = 0x010104f5, ID_RESIZEABLE = 0x010104f6, ID_MAX_ASPECT = 0x01010560, ID_MIN_ASPECT = 0x0101059b;

    static final int ID_NAME = 0x01010003, ID_EXPORTED = 0x01010010, ID_AUTHORITIES = 0x01010018, ID_INIT_ORDER = 0x0101001a;
    static final int T_STRING = 0x03;
    static final int T_INT_DEC = 0x10, T_INT_HEX = 0x11, T_BOOL = 0x12, T_DIMEN = 0x05;
    /** orientation|keyboardHidden|screenLayout|uiMode|screenSize|smallestScreenSize|density */
    static final int CONFIG_BITS = 0x80 | 0x20 | 0x100 | 0x200 | 0x400 | 0x800 | 0x1000;

    static int dp(int v) { return (v << 8) | 1; }   // complex dimension, unit dip

    // ---------------- model ----------------
    static final class Attr { int ns, name, raw, type, data; }

    static final class Node {
        int type, line, comment;
        int[] ext;                 // non-element chunks: their extension ints (string refs)
        int ns, name, idIdx, classIdx, styleIdx;   // element
        List<Attr> attrs = new ArrayList<>();
    }

    private final List<String> strings = new ArrayList<>();
    private boolean utf8;
    private int poolFlags;
    private final List<Integer> resMap = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private int androidNs = -1;

    private ManifestWindowPatch() {}

    /** Returns the patched manifest, or null if the file uses features this editor doesn't handle. */
    public static byte[] patch(byte[] axml, int defaultWdp, int defaultHdp, int minDp) {
        try {
            ManifestWindowPatch m = new ManifestWindowPatch();
            if (!m.parse(axml)) return null;
            m.apply(defaultWdp, defaultHdp, minDp);
            return m.write();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Floating clones: our FloatInit provider is added (it makes every screen a floating window at runtime),
     * forced orientation / aspect limits are removed and size changes don't restart activities.
     */
    public static byte[] patchFloat(byte[] axml, String pkg, int slot) {
        try {
            ManifestWindowPatch m = new ManifestWindowPatch();
            if (!m.parse(axml)) return null;
            m.applyFloat(pkg, slot);
            return m.write();
        } catch (Exception e) {
            return null;
        }
    }

    private void setString(Node n, int id, String plain, String value) {
        set(n, id, plain, T_STRING, 0);
        Attr a = find(n, id);
        int idx = plainString(value);
        a.raw = idx;
        a.data = idx;
    }

    private Node element(String name, int line) {
        Node n = new Node();
        n.type = START_EL;
        n.line = line;
        n.comment = -1;
        n.ns = -1;
        n.name = plainString(name);
        return n;
    }

    private Node end(Node start) {
        Node e = new Node();
        e.type = END_EL;
        e.line = start.line;
        e.comment = -1;
        e.ext = new int[]{-1, start.name};
        return e;
    }

    private void applyFloat(String pkg, int slot) {
        attrName(ID_NAME, "name");
        attrName(ID_EXPORTED, "exported");
        attrName(ID_AUTHORITIES, "authorities");
        attrName(ID_INIT_ORDER, "initOrder");
        attrName(ID_SCREEN_ORIENTATION, "screenOrientation");
        attrName(ID_CONFIG_CHANGES, "configChanges");
        List<Node> out = new ArrayList<>();
        boolean addedPerm = false;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            String en = n.type == START_EL ? nameOf(n) : "";
            if (en.equals("application") && !addedPerm) {
                // <uses-permission android:name="android.permission.REORDER_TASKS"/> just before <application>
                Node up = element("uses-permission", n.line);
                setString(up, ID_NAME, "name", "android.permission.REORDER_TASKS");
                out.add(up);
                out.add(end(up));
                addedPerm = true;
            }
            out.add(n);
            if (n.type != START_EL) continue;
            if (en.equals("application")) {
                Node pr = element("provider", n.line);
                setString(pr, ID_NAME, "name", "com.worst.floatwin.FloatInit");
                set(pr, ID_EXPORTED, "exported", T_BOOL, 0);
                setString(pr, ID_AUTHORITIES, "authorities", pkg + ".worstfloat." + slot);
                set(pr, ID_INIT_ORDER, "initOrder", T_INT_DEC, 1000);
                out.add(pr);
                out.add(end(pr));
            } else if (en.equals("activity") || en.equals("activity-alias")) {
                Attr so = find(n, ID_SCREEN_ORIENTATION);
                if (so != null) { so.raw = -1; so.type = T_INT_DEC; so.data = -1; }
                for (int k = n.attrs.size() - 1; k >= 0; k--) {
                    int id = idOf(n.attrs.get(k));
                    if (id == ID_MAX_ASPECT || id == ID_MIN_ASPECT) n.attrs.remove(k);
                }
                if (en.equals("activity")) {
                    Attr cc = find(n, ID_CONFIG_CHANGES);
                    int bits = cc != null && (cc.type == T_INT_HEX || cc.type == T_INT_DEC) ? cc.data : 0;
                    set(n, ID_CONFIG_CHANGES, "configChanges", T_INT_HEX, bits | CONFIG_BITS);
                }
            }
        }
        nodes.clear();
        nodes.addAll(out);
    }

    // ---------------- parse ----------------
    private boolean parse(byte[] d) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != RES_XML) return false;
        int off = b.getShort(2) & 0xffff, end = Math.min(b.getInt(4), d.length);
        while (off + 8 <= end) {
            int type = b.getShort(off) & 0xffff, hs = b.getShort(off + 2) & 0xffff, size = b.getInt(off + 4);
            if (size < 8) return false;
            if (type == RES_STRING_POOL) {
                int count = b.getInt(off + 8), styles = b.getInt(off + 12);
                if (styles != 0) return false;
                poolFlags = b.getInt(off + 16);
                utf8 = (poolFlags & UTF8) != 0;
                int strStart = b.getInt(off + 20);
                for (int i = 0; i < count; i++) strings.add(readString(d, b, off + strStart + b.getInt(off + hs + i * 4)));
            } else if (type == RES_RESOURCE_MAP) {
                for (int i = 0; i < (size - hs) / 4; i++) resMap.add(b.getInt(off + hs + i * 4));
            } else if (type >= START_NS && type <= CDATA) {
                Node n = new Node();
                n.type = type;
                n.line = b.getInt(off + 8);
                n.comment = b.getInt(off + 12);
                if (type == START_EL) {
                    n.ns = b.getInt(off + 16);
                    n.name = b.getInt(off + 20);
                    int aStart = b.getShort(off + 24) & 0xffff, aSize = b.getShort(off + 26) & 0xffff, aCount = b.getShort(off + 28) & 0xffff;
                    n.idIdx = b.getShort(off + 30) & 0xffff;
                    n.classIdx = b.getShort(off + 32) & 0xffff;
                    n.styleIdx = b.getShort(off + 34) & 0xffff;
                    for (int i = 0; i < aCount; i++) {
                        int ao = off + 16 + aStart + i * aSize;
                        Attr a = new Attr();
                        a.ns = b.getInt(ao);
                        a.name = b.getInt(ao + 4);
                        a.raw = b.getInt(ao + 8);
                        a.type = d[ao + 15] & 0xff;
                        a.data = b.getInt(ao + 16);
                        n.attrs.add(a);
                    }
                } else {
                    int ints = (size - hs) / 4;
                    n.ext = new int[ints];
                    for (int i = 0; i < ints; i++) n.ext[i] = b.getInt(off + hs + i * 4);
                    if (type == START_NS && n.ext.length >= 2 && n.ext[1] >= 0 && n.ext[1] < strings.size()
                            && "http://schemas.android.com/apk/res/android".equals(strings.get(n.ext[1]))) androidNs = n.ext[1];
                }
                nodes.add(n);
            }
            off += size;
        }
        return !strings.isEmpty() && androidNs >= 0;
    }

    private String readString(byte[] d, ByteBuffer b, int p) throws Exception {
        if (utf8) {
            int x = d[p] & 0xff; p++;
            if ((x & 0x80) != 0) p++;
            int len = d[p] & 0xff; p++;
            if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (d[p] & 0xff); p++; }
            return new String(d, p, len, "UTF-8");
        }
        int len = b.getShort(p) & 0xffff; p += 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(p) & 0xffff); p += 2; }
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = (char) (b.getShort(p + i * 2) & 0xffff);
        return new String(c);
    }

    // ---------------- strings / ids ----------------

    /** String index for an attribute name with this resource id (must live inside the resource-map region). */
    private int attrName(int id, String plain) {
        int i = resMap.indexOf(id);
        if (i >= 0) return i;
        int at = resMap.size();          // insert right after the mapped region
        strings.add(at, plain);
        resMap.add(id);
        shiftRefs(at);
        return at;
    }

    /** After inserting a string at index {@code at}, every reference >= at moves up by one. */
    private void shiftRefs(int at) {
        if (androidNs >= at) androidNs++;
        for (Node n : nodes) {
            n.comment = sh(n.comment, at);
            if (n.type == START_EL) {
                n.ns = sh(n.ns, at);
                n.name = sh(n.name, at);
                for (Attr a : n.attrs) {
                    a.ns = sh(a.ns, at);
                    a.name = sh(a.name, at);
                    a.raw = sh(a.raw, at);
                    if (a.type == 0x03) a.data = sh(a.data, at);
                }
            } else if (n.type == END_EL || n.type == START_NS || n.type == END_NS) {
                for (int i = 0; i < n.ext.length; i++) n.ext[i] = sh(n.ext[i], at);
            } else if (n.type == CDATA && n.ext.length > 0) {
                n.ext[0] = sh(n.ext[0], at);
                if (n.ext.length >= 4 && (n.ext[2] >>> 24) == 0x03) n.ext[3] = sh(n.ext[3], at);
            }
        }
    }

    private static int sh(int v, int at) { return v >= at ? v + 1 : v; }

    private int plainString(String s) {
        int i = strings.lastIndexOf(s);
        if (i >= resMap.size()) return i;
        strings.add(s);
        return strings.size() - 1;
    }

    private int idOf(Attr a) { return a.name >= 0 && a.name < resMap.size() ? resMap.get(a.name) : 0; }

    private Attr find(Node n, int id) {
        for (Attr a : n.attrs) if (idOf(a) == id) return a;
        return null;
    }

    /** Sets (or inserts, keeping ascending resource-id order) an android: attribute with a typed value. */
    private void set(Node n, int id, String plain, int type, int data) {
        Attr a = find(n, id);
        if (a == null) {
            int nameIdx = attrName(id, plain);
            a = new Attr();
            a.ns = androidNs;
            a.name = nameIdx;
            int pos = n.attrs.size();
            for (int i = 0; i < n.attrs.size(); i++) {
                int other = idOf(n.attrs.get(i));
                if (other != 0 && (other & 0xffffffffL) > (id & 0xffffffffL)) { pos = i; break; }
            }
            n.attrs.add(pos, a);
        }
        a.raw = -1;
        a.type = type;
        a.data = data;
    }

    private String nameOf(Node n) { return n.name >= 0 && n.name < strings.size() ? strings.get(n.name) : ""; }

    // ---------------- the actual changes ----------------
    private void apply(int defW, int defH, int minDp) {
        // make sure all attribute names exist first (this may shift indices)
        attrName(ID_SCREEN_ORIENTATION, "screenOrientation");
        attrName(ID_CONFIG_CHANGES, "configChanges");
        attrName(ID_GRAVITY, "gravity");
        attrName(ID_MIN_WIDTH, "minWidth");
        attrName(ID_MIN_HEIGHT, "minHeight");
        attrName(ID_DEFAULT_WIDTH, "defaultWidth");
        attrName(ID_DEFAULT_HEIGHT, "defaultHeight");
        attrName(ID_RESIZEABLE, "resizeableActivity");

        List<Node> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            out.add(n);
            if (n.type != START_EL) continue;
            String en = nameOf(n);
            if (en.equals("application")) {
                set(n, ID_RESIZEABLE, "resizeableActivity", T_BOOL, -1);
            } else if (en.equals("activity") || en.equals("activity-alias")) {
                set(n, ID_RESIZEABLE, "resizeableActivity", T_BOOL, -1);
                Attr so = find(n, ID_SCREEN_ORIENTATION);
                if (so != null) { so.raw = -1; so.type = T_INT_DEC; so.data = -1; }   // unspecified
                // drop aspect-ratio limits
                for (int k = n.attrs.size() - 1; k >= 0; k--) {
                    int id = idOf(n.attrs.get(k));
                    if (id == ID_MAX_ASPECT || id == ID_MIN_ASPECT) n.attrs.remove(k);
                }
                if (en.equals("activity")) {
                    Attr cc = find(n, ID_CONFIG_CHANGES);
                    int bits = cc != null && (cc.type == T_INT_HEX || cc.type == T_INT_DEC) ? cc.data : 0;
                    set(n, ID_CONFIG_CHANGES, "configChanges", T_INT_HEX, bits | CONFIG_BITS);
                    if (!hasLayoutChild(i)) {
                        Node lay = new Node();
                        lay.type = START_EL;
                        lay.line = n.line;
                        lay.comment = -1;
                        lay.ns = -1;
                        lay.name = plainString("layout");
                        set(lay, ID_GRAVITY, "gravity", T_INT_HEX, 0x33);           // top|left
                        set(lay, ID_MIN_WIDTH, "minWidth", T_DIMEN, dp(minDp));
                        set(lay, ID_MIN_HEIGHT, "minHeight", T_DIMEN, dp(minDp));
                        set(lay, ID_DEFAULT_WIDTH, "defaultWidth", T_DIMEN, dp(defW));
                        set(lay, ID_DEFAULT_HEIGHT, "defaultHeight", T_DIMEN, dp(defH));
                        Node layEnd = new Node();
                        layEnd.type = END_EL;
                        layEnd.line = n.line;
                        layEnd.comment = -1;
                        layEnd.ext = new int[]{-1, lay.name};
                        out.add(lay);
                        out.add(layEnd);
                    }
                }
            }
        }
        nodes.clear();
        nodes.addAll(out);
    }

    /** True if the activity starting at nodes[i] already has a <layout> child. */
    private boolean hasLayoutChild(int i) {
        int depth = 0;
        for (int k = i + 1; k < nodes.size(); k++) {
            Node n = nodes.get(k);
            if (n.type == START_EL) {
                if (depth == 0 && nameOf(n).equals("layout")) return true;
                depth++;
            } else if (n.type == END_EL) {
                if (depth == 0) return false;
                depth--;
            }
        }
        return false;
    }

    // ---------------- write ----------------
    private byte[] write() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(pool());
        // resource map
        ByteBuffer rm = le(8 + resMap.size() * 4);
        rm.putShort((short) RES_RESOURCE_MAP).putShort((short) 8).putInt(8 + resMap.size() * 4);
        for (int id : resMap) rm.putInt(id);
        body.write(rm.array());
        for (Node n : nodes) body.write(node(n));
        byte[] bb = body.toByteArray();
        ByteBuffer all = le(8 + bb.length);
        all.putShort((short) RES_XML).putShort((short) 8).putInt(8 + bb.length).put(bb);
        return all.array();
    }

    private byte[] pool() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offs[i] = data.size();
            byte[] e = encode(strings.get(i));
            data.write(e);
        }
        while (data.size() % 4 != 0) data.write(0);
        int hs = 28, count = strings.size();
        int strStart = hs + count * 4;
        int size = strStart + data.size();
        ByteBuffer p = le(size);
        p.putShort((short) RES_STRING_POOL).putShort((short) hs).putInt(size).putInt(count).putInt(0)
                .putInt(poolFlags & ~1).putInt(strStart).putInt(0);
        for (int o : offs) p.putInt(o);
        p.put(data.toByteArray());
        return p.array();
    }

    private byte[] encode(String s) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (utf8) {
            byte[] u = s.getBytes("UTF-8");
            len8(o, s.length());
            len8(o, u.length);
            o.write(u);
            o.write(0);
        } else {
            int len = s.length();
            if (len > 0x7fff) { u16(o, 0x8000 | (len >> 16)); u16(o, len & 0xffff); } else u16(o, len);
            for (int i = 0; i < len; i++) u16(o, s.charAt(i));
            u16(o, 0);
        }
        return o.toByteArray();
    }

    private static void len8(ByteArrayOutputStream o, int l) {
        if (l > 0x7f) { o.write(0x80 | ((l >> 8) & 0x7f)); o.write(l & 0xff); } else o.write(l);
    }

    private static void u16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }

    private byte[] node(Node n) {
        if (n.type == START_EL) {
            int size = 16 + 20 + n.attrs.size() * 20;
            ByteBuffer b = le(size);
            b.putShort((short) START_EL).putShort((short) 16).putInt(size).putInt(n.line).putInt(n.comment)
                    .putInt(n.ns).putInt(n.name).putShort((short) 20).putShort((short) 20).putShort((short) n.attrs.size());
            // id/class/style attribute positions (1-based; 0 = none) are recomputed by name
            b.putShort((short) 0).putShort((short) 0).putShort((short) 0);
            for (Attr a : n.attrs) {
                b.putInt(a.ns).putInt(a.name).putInt(a.raw).putShort((short) 8).put((byte) 0).put((byte) a.type).putInt(a.data);
            }
            return b.array();
        }
        int size = 16 + n.ext.length * 4;
        ByteBuffer b = le(size);
        b.putShort((short) n.type).putShort((short) 16).putInt(size).putInt(n.line).putInt(n.comment);
        for (int v : n.ext) b.putInt(v);
        return b.array();
    }

    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
}
