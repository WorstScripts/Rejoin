package com.multirejoin.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the files behind an app's launcher icon in resources.arsc, so they can be replaced.
 * Adaptive-icon XML variants are switched off (entry set to "no entry") so Android falls back to the
 * picture files, which are then replaced with the new icon. Edits are done in place (same size).
 */
public final class ArscIcon {
    private static final int RES_STRING_POOL = 0x0001, RES_TABLE = 0x0002, RES_PACKAGE = 0x0200, RES_TYPE = 0x0201;
    private static final int UTF8 = 0x100;
    private static final int TYPE_STRING = 0x03;

    public final byte[] arsc;          // edited copy
    public final Set<String> pictures = new LinkedHashSet<>();   // png/webp files of the icon
    public int disabledXml = 0;
    private final ByteBuffer b;
    private final List<String> strings = new ArrayList<>();

    private ArscIcon(byte[] src) {
        arsc = src.clone();
        b = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Returns null if the icon can't be handled safely (then the icon is left as it is). */
    public static ArscIcon prepare(byte[] arsc, int... resIds) {
        try {
            ArscIcon a = new ArscIcon(arsc);
            if (!a.run(resIds)) return null;
            return a.pictures.isEmpty() ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    private int u16(int o) { return b.getShort(o) & 0xffff; }

    private boolean run(int[] ids) throws Exception {
        if (u16(0) != RES_TABLE) return false;
        int off = u16(2);
        // global string pool comes first: it holds the file paths
        if (u16(off) != RES_STRING_POOL) return false;
        readPool(off);
        off += b.getInt(off + 4);
        List<int[]> xmlSlots = new ArrayList<>();   // {position of offset field, width (2 or 4)}
        boolean anyPicture = false;
        while (off + 8 <= arsc.length) {
            int type = u16(off), size = b.getInt(off + 4);
            if (size <= 0) break;
            if (type == RES_PACKAGE) {
                int pkgId = b.getInt(off + 8);
                int hs = u16(off + 2);
                int p = off + hs;
                int end = off + size;
                while (p + 8 <= end) {
                    int t = u16(p), sz = b.getInt(p + 4);
                    if (sz <= 0) break;
                    if (t == RES_TYPE) {
                        for (int id : ids) {
                            if (((id >>> 24) & 0xff) != pkgId) continue;
                            int typeId = (id >>> 16) & 0xff, entry = id & 0xffff;
                            if ((arsc[p + 8] & 0xff) != typeId) continue;
                            int r = entryOf(p, entry);
                            if (r < 0) continue;
                            int[] slot = slotOf(p, entry);
                            String path = valueString(r);
                            if (path == null) continue;
                            String low = path.toLowerCase();
                            if (low.endsWith(".xml")) { if (slot != null) xmlSlots.add(slot); }
                            else if (low.endsWith(".png") || low.endsWith(".webp")) { pictures.add(path); anyPicture = true; }
                        }
                    }
                    p += sz;
                }
            }
            off += size;
        }
        if (!anyPicture) return false;          // only adaptive icons: leave it alone (safe)
        for (int[] s : xmlSlots) {
            if (s[1] == 4) b.putInt(s[0], 0xffffffff); else b.putShort(s[0], (short) 0xffff);
            disabledXml++;
        }
        return true;
    }

    /** Position of the entry offset field for this entry in a type chunk: {pos, width}, or null (sparse). */
    private int[] slotOf(int t, int entry) {
        int flags = arsc[t + 9] & 0xff;
        int hs = u16(t + 2);
        int count = b.getInt(t + 12);
        if ((flags & 0x01) != 0) return null;                // sparse table: not edited
        if (entry >= count) return null;
        boolean off16 = (flags & 0x02) != 0;
        return off16 ? new int[]{t + hs + entry * 2, 2} : new int[]{t + hs + entry * 4, 4};
    }

    /** Absolute position of the entry in a type chunk, or -1 if this config has no such entry. */
    private int entryOf(int t, int entry) {
        int flags = arsc[t + 9] & 0xff;
        int hs = u16(t + 2);
        int count = b.getInt(t + 12);
        int entriesStart = b.getInt(t + 16);
        if ((flags & 0x01) != 0) {
            // sparse: pairs of (u16 index, u16 offset/4)
            for (int i = 0; i < count; i++) {
                int idx = u16(t + hs + i * 4), o = u16(t + hs + i * 4 + 2);
                if (idx == entry) return t + entriesStart + o * 4;
            }
            return -1;
        }
        if (entry >= count) return -1;
        int o;
        if ((flags & 0x02) != 0) {
            int v = u16(t + hs + entry * 2);
            if (v == 0xffff) return -1;
            o = v * 4;
        } else {
            o = b.getInt(t + hs + entry * 4);
            if (o == -1) return -1;
        }
        return t + entriesStart + o;
    }

    /** The string value (file path) of an entry, or null. */
    private String valueString(int e) {
        int eflags = u16(e + 2);
        if ((eflags & 0x0008) != 0) {          // compact entry: data in the last 4 bytes, type in high flag bits
            int type = (eflags >>> 8) & 0xff;
            return type == TYPE_STRING ? str(b.getInt(e + 4)) : null;
        }
        if ((eflags & 0x0001) != 0) return null;  // complex (bag) entry
        int esize = u16(e);
        int v = e + esize;
        int type = arsc[v + 3] & 0xff;
        if (type != TYPE_STRING) return null;
        return str(b.getInt(v + 4));
    }

    private String str(int i) { return i >= 0 && i < strings.size() ? strings.get(i) : null; }

    private void readPool(int off) throws Exception {
        int hs = u16(off + 2), count = b.getInt(off + 8), flags = b.getInt(off + 16), start = b.getInt(off + 20);
        boolean utf8 = (flags & UTF8) != 0;
        for (int i = 0; i < count; i++) {
            int p = off + start + b.getInt(off + hs + i * 4);
            if (utf8) {
                int x = arsc[p] & 0xff; p++;
                if ((x & 0x80) != 0) p++;
                int len = arsc[p] & 0xff; p++;
                if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (arsc[p] & 0xff); p++; }
                strings.add(new String(arsc, p, len, "UTF-8"));
            } else {
                int len = u16(p); p += 2;
                if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | u16(p); p += 2; }
                char[] c = new char[len];
                for (int k = 0; k < len; k++) c[k] = (char) u16(p + k * 2);
                strings.add(new String(c));
            }
        }
    }
}
