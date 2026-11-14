package com.multirejoin.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal reader/patcher for Android binary XML (AndroidManifest.xml inside an APK).
 * Used to rename an app's package so it can be installed next to the original.
 * Existing strings are kept byte-for-byte; changed values are appended as new strings.
 */
public final class Axml {
    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML = 0x0003;
    private static final int RES_XML_RESOURCE_MAP = 0x0180;
    private static final int RES_XML_START_ELEMENT = 0x0102;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int UTF8_FLAG = 0x100;

    // android:* attribute resource ids
    private static final int A_LABEL = 0x01010001;
    private static final int A_NAME = 0x01010003;
    private static final int A_PERMISSION = 0x01010006;
    private static final int A_READ_PERMISSION = 0x01010007;
    private static final int A_WRITE_PERMISSION = 0x01010008;
    private static final int A_SHARED_USER_ID = 0x0101000b;
    private static final int A_TASK_AFFINITY = 0x01010012;
    private static final int A_AUTHORITIES = 0x01010018;
    private static final int A_MIN_SDK = 0x0101020c;

    private final byte[] data;
    private final ByteBuffer bb;
    private int poolStart, poolSize, strCount, styleCount, flags, stringsStart, stylesStart;
    private final List<String> strings = new ArrayList<>();
    private int[] resMap = new int[0];
    private final List<Integer> elementOffsets = new ArrayList<>();

    public Axml(byte[] data) {
        this.data = data;
        this.bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        parse();
    }

    private int u16(int off) { return bb.getShort(off) & 0xffff; }
    private int i32(int off) { return bb.getInt(off); }

    private void parse() {
        if (u16(0) != RES_XML) throw new IllegalArgumentException("Not a binary XML file");
        int fileHeader = u16(2);
        int end = Math.min(i32(4), data.length);
        int off = fileHeader;
        while (off + 8 <= end) {
            int type = u16(off);
            int size = i32(off + 4);
            if (size < 8) break;
            if (type == RES_STRING_POOL && poolSize == 0) {
                poolStart = off;
                poolSize = size;
                strCount = i32(off + 8);
                styleCount = i32(off + 12);
                flags = i32(off + 16);
                stringsStart = i32(off + 20);
                stylesStart = i32(off + 24);
                for (int i = 0; i < strCount; i++) strings.add(readString(i));
            } else if (type == RES_XML_RESOURCE_MAP) {
                int hs = u16(off + 2);
                int n = (size - hs) / 4;
                resMap = new int[n];
                for (int i = 0; i < n; i++) resMap[i] = i32(off + hs + i * 4);
            } else if (type == RES_XML_START_ELEMENT) {
                elementOffsets.add(off);
            }
            off += size;
        }
        if (poolSize == 0) throw new IllegalArgumentException("No string pool");
    }

    private String readString(int idx) {
        int hs = u16(poolStart + 2);
        int so = i32(poolStart + hs + idx * 4);
        int p = poolStart + stringsStart + so;
        try {
            if ((flags & UTF8_FLAG) != 0) {
                int b = data[p] & 0xff; p++;
                if ((b & 0x80) != 0) p++;
                int len = data[p] & 0xff; p++;
                if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (data[p] & 0xff); p++; }
                return new String(data, p, len, "UTF-8");
            } else {
                int len = u16(p); p += 2;
                if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | u16(p); p += 2; }
                char[] c = new char[len];
                for (int i = 0; i < len; i++) c[i] = (char) u16(p + i * 2);
                return new String(c);
            }
        } catch (Exception e) {
            return "";
        }
    }

    public String str(int idx) { return idx >= 0 && idx < strings.size() ? strings.get(idx) : null; }

    private int resId(int nameIdx) { return nameIdx >= 0 && nameIdx < resMap.length ? resMap[nameIdx] : 0; }

    // ---------- element / attribute access ----------

    private String elemName(int eo) { return str(i32(eo + 20)); }
    private int attrCount(int eo) { return u16(eo + 28); }
    private int attrOff(int eo, int i) {
        int attrStart = u16(eo + 24);
        int attrSize = u16(eo + 26);
        return eo + 16 + attrStart + i * attrSize;
    }

    /** The manifest's package name. */
    public String getPackage() {
        for (int eo : elementOffsets) {
            if (!"manifest".equals(elemName(eo))) continue;
            for (int i = 0; i < attrCount(eo); i++) {
                int ao = attrOff(eo, i);
                if ("package".equals(str(i32(ao + 4)))) {
                    int raw = i32(ao + 8);
                    if (raw >= 0) return str(raw);
                    return str(i32(ao + 16));
                }
            }
        }
        return null;
    }

    /** True when this manifest belongs to a split APK (has a "split" attribute). */
    public boolean isSplit() {
        for (int eo : elementOffsets) {
            if (!"manifest".equals(elemName(eo))) continue;
            for (int i = 0; i < attrCount(eo); i++) {
                if ("split".equals(str(i32(attrOff(eo, i) + 4)))) return true;
            }
            return false;
        }
        return false;
    }

    /** Resource ids of android:icon and android:roundIcon on <application> (0 when missing). */
    public int[] iconRefs() {
        int icon = 0, round = 0;
        for (int eo : elementOffsets) {
            if (!"application".equals(elemName(eo))) continue;
            for (int i = 0; i < attrCount(eo); i++) {
                int ao = attrOff(eo, i);
                int id = resId(i32(ao + 4));
                int type = data[ao + 15] & 0xff;
                if (type != 0x01) continue;                 // reference
                if (id == 0x01010002) icon = i32(ao + 16);
                if (id == 0x0101052c) round = i32(ao + 16);
            }
        }
        return new int[]{icon, round};
    }

    public int getMinSdk() {
        for (int eo : elementOffsets) {
            if (!"uses-sdk".equals(elemName(eo))) continue;
            for (int i = 0; i < attrCount(eo); i++) {
                int ao = attrOff(eo, i);
                int nameIdx = i32(ao + 4);
                if (resId(nameIdx) == A_MIN_SDK || "minSdkVersion".equals(str(nameIdx))) {
                    int type = data[ao + 15] & 0xff;
                    if (type >= TYPE_INT_DEC && type <= 0x1f) return i32(ao + 16);
                    try { return Integer.parseInt(str(i32(ao + 8))); } catch (Exception e) { return 1; }
                }
            }
        }
        return 1;
    }

    // ---------- renaming ----------

    private static boolean isPermElem(String e) {
        return "permission".equals(e) || "permission-group".equals(e) || "permission-tree".equals(e);
    }

    private static boolean isUsesPermElem(String e) {
        return "uses-permission".equals(e) || "uses-permission-sdk-23".equals(e) || "uses-permission-sdk-m".equals(e);
    }

    /**
     * Rename package oldPkg -> newPkg. Also renames provider authorities, the app's own
     * permissions and task affinity, so the clone does not conflict with the original.
     * Returns the new manifest bytes.
     */
    public byte[] renamePackage(String oldPkg, String newPkg) { return renamePackage(oldPkg, newPkg, null); }

    /** Same as above, and when label is not null the app's visible name is changed too. */
    public byte[] renamePackage(String oldPkg, String newPkg, String label) {
        String tag = newPkg.substring(newPkg.lastIndexOf('.') + 1);
        // pass 1: permissions declared by this app
        Map<String, String> permMap = new HashMap<>();
        for (int eo : elementOffsets) {
            if (!isPermElem(elemName(eo))) continue;
            for (int i = 0; i < attrCount(eo); i++) {
                int ao = attrOff(eo, i);
                if (!isName(i32(ao + 4), A_NAME, "name")) continue;
                String v = stringValue(ao);
                if (v == null || v.isEmpty()) continue;
                permMap.put(v, v.startsWith(oldPkg) ? newPkg + v.substring(oldPkg.length()) : v + "." + tag);
            }
        }

        List<String> added = new ArrayList<>();
        Map<String, Integer> newIndex = new HashMap<>();
        byte[] out = data.clone();
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);

        for (int eo : elementOffsets) {
            String en = elemName(eo);
            for (int i = 0; i < attrCount(eo); i++) {
                int ao = attrOff(eo, i);
                int nameIdx = i32(ao + 4);
                String v = stringValue(ao);
                String nv = null;
                if (label != null && isName(nameIdx, A_LABEL, "label")
                        && ("application".equals(en) || "activity".equals(en) || "activity-alias".equals(en))) {
                    nv = label;
                    v = "";
                }
                if (v == null) continue;
                if ("manifest".equals(en) && "package".equals(str(nameIdx)) && i32(ao) == -1) {
                    if (v.equals(oldPkg)) nv = newPkg;
                } else if (isName(nameIdx, A_AUTHORITIES, "authorities")) {
                    StringBuilder sb = new StringBuilder();
                    for (String t : v.split(";")) {
                        if (sb.length() > 0) sb.append(';');
                        if (t.startsWith(oldPkg)) sb.append(newPkg).append(t.substring(oldPkg.length()));
                        else sb.append(t).append('.').append(tag);
                    }
                    nv = sb.toString();
                } else if (isName(nameIdx, A_NAME, "name") && (isPermElem(en) || isUsesPermElem(en))) {
                    if (permMap.containsKey(v)) nv = permMap.get(v);
                    else if (v.startsWith(oldPkg + ".")) nv = newPkg + v.substring(oldPkg.length());
                } else if (isName(nameIdx, A_PERMISSION, "permission")
                        || isName(nameIdx, A_READ_PERMISSION, "readPermission")
                        || isName(nameIdx, A_WRITE_PERMISSION, "writePermission")) {
                    if (permMap.containsKey(v)) nv = permMap.get(v);
                    else if (v.startsWith(oldPkg + ".")) nv = newPkg + v.substring(oldPkg.length());
                } else if (isName(nameIdx, A_TASK_AFFINITY, "taskAffinity")) {
                    if (v.startsWith(oldPkg)) nv = newPkg + v.substring(oldPkg.length());
                } else if (isName(nameIdx, A_SHARED_USER_ID, "sharedUserId")) {
                    nv = v + "." + tag;
                }
                if (nv == null || nv.equals(v)) continue;
                Integer idx = newIndex.get(nv);
                if (idx == null) {
                    idx = strCount + added.size();
                    added.add(nv);
                    newIndex.put(nv, idx);
                }
                ob.putInt(ao + 8, idx);
                ob.put(ao + 15, (byte) TYPE_STRING);
                ob.putInt(ao + 16, idx);
            }
        }
        if (added.isEmpty()) return out;
        return rebuildPool(out, added);
    }

    private boolean isName(int nameIdx, int id, String plain) {
        int r = resId(nameIdx);
        if (r != 0) return r == id;
        return plain.equals(str(nameIdx));
    }

    private String stringValue(int ao) {
        int raw = i32(ao + 8);
        int type = data[ao + 15] & 0xff;
        if (raw >= 0) return str(raw);
        if (type == TYPE_STRING) return str(i32(ao + 16));
        return null;
    }

    private byte[] rebuildPool(byte[] cur, List<String> added) {
        ByteBuffer cb = ByteBuffer.wrap(cur).order(ByteOrder.LITTLE_ENDIAN);
        int hs = cb.getShort(poolStart + 2) & 0xffff;
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        int strDataEnd = styleCount > 0 ? stylesStart : poolSize;
        int oldStrDataLen = strDataEnd - stringsStart;

        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        int[] addedOffsets = new int[added.size()];
        for (int i = 0; i < added.size(); i++) {
            addedOffsets[i] = oldStrDataLen + extra.size();
            byte[] enc = encode(added.get(i), utf8);
            extra.write(enc, 0, enc.length);
        }
        int newStrDataLen = oldStrDataLen + extra.size();
        int pad = (4 - (newStrDataLen % 4)) % 4;
        newStrDataLen += pad;

        int styleDataLen = styleCount > 0 ? poolSize - stylesStart : 0;
        int newCount = strCount + added.size();
        int newStringsStart = hs + newCount * 4 + styleCount * 4;
        int newStylesStart = styleCount > 0 ? newStringsStart + newStrDataLen : 0;
        int newPoolSize = newStringsStart + newStrDataLen + styleDataLen;

        ByteBuffer np = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN);
        np.put(cur, poolStart, hs);
        np.putInt(4, newPoolSize);
        np.putInt(8, newCount);
        np.putInt(16, flags & ~1); // no longer sorted
        np.putInt(20, newStringsStart);
        np.putInt(24, newStylesStart);
        np.position(hs);
        for (int i = 0; i < strCount; i++) np.putInt(cb.getInt(poolStart + hs + i * 4));
        for (int o : addedOffsets) np.putInt(o);
        for (int i = 0; i < styleCount; i++) np.putInt(cb.getInt(poolStart + hs + strCount * 4 + i * 4));
        np.put(cur, poolStart + stringsStart, oldStrDataLen);
        np.put(extra.toByteArray());
        for (int i = 0; i < pad; i++) np.put((byte) 0);
        if (styleDataLen > 0) np.put(cur, poolStart + stylesStart, styleDataLen);

        ByteArrayOutputStream res = new ByteArrayOutputStream();
        res.write(cur, 0, poolStart);
        res.write(np.array(), 0, newPoolSize);
        res.write(cur, poolStart + poolSize, cur.length - poolStart - poolSize);
        byte[] r = res.toByteArray();
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(4, r.length);
        return r;
    }

    private static byte[] encode(String s, boolean utf8) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            if (utf8) {
                byte[] b = s.getBytes("UTF-8");
                writeLen8(o, s.length());
                writeLen8(o, b.length);
                o.write(b);
                o.write(0);
            } else {
                int len = s.length();
                if (len > 0x7fff) {
                    writeU16(o, 0x8000 | (len >> 16));
                    writeU16(o, len & 0xffff);
                } else {
                    writeU16(o, len);
                }
                for (int i = 0; i < len; i++) writeU16(o, s.charAt(i));
                writeU16(o, 0);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7f) {
            o.write(0x80 | ((len >> 8) & 0x7f));
            o.write(len & 0xff);
        } else {
            o.write(len);
        }
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }
}
