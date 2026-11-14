package com.multirejoin.app;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Raw ZIP access for APKs: read entries and copy an APK while replacing one entry, without recompressing. */
public final class ApkZip {
    public static final class Entry {
        public String name;
        int versionNeeded, flags, method, time, date;
        long crc, compSize, size, localOffset;
        long dataOffset;
    }

    public final List<Entry> entries = new ArrayList<>();
    private final RandomAccessFile raf;

    public ApkZip(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        readCentralDirectory();
    }

    public void close() { try { raf.close(); } catch (IOException ignored) {} }

    private static int u16(ByteBuffer b, int o) { return b.getShort(o) & 0xffff; }
    private static long u32(ByteBuffer b, int o) { return b.getInt(o) & 0xffffffffL; }

    private void readCentralDirectory() throws IOException {
        long len = raf.length();
        int tail = (int) Math.min(len, 65557);
        byte[] t = new byte[tail];
        raf.seek(len - tail);
        raf.readFully(t);
        ByteBuffer tb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (tb.getInt(i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("Not a zip/apk file");
        long cdSize = u32(tb, eocd + 12);
        long cdOffset = u32(tb, eocd + 16);
        byte[] cd = new byte[(int) cdSize];
        raf.seek(cdOffset);
        raf.readFully(cd);
        ByteBuffer b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        int p = 0;
        byte[] lh = new byte[30];
        ByteBuffer lb = ByteBuffer.wrap(lh).order(ByteOrder.LITTLE_ENDIAN);
        while (p + 46 <= cd.length && b.getInt(p) == 0x02014b50) {
            Entry e = new Entry();
            e.versionNeeded = u16(b, p + 6);
            e.flags = u16(b, p + 8);
            e.method = u16(b, p + 10);
            e.time = u16(b, p + 12);
            e.date = u16(b, p + 14);
            e.crc = u32(b, p + 16);
            e.compSize = u32(b, p + 20);
            e.size = u32(b, p + 24);
            int nl = u16(b, p + 28), el = u16(b, p + 30), cl = u16(b, p + 32);
            e.localOffset = u32(b, p + 42);
            e.name = new String(cd, p + 46, nl, "UTF-8");
            p += 46 + nl + el + cl;
            raf.seek(e.localOffset);
            raf.readFully(lh);
            if (lb.getInt(0) != 0x04034b50) throw new IOException("Bad local header: " + e.name);
            e.dataOffset = e.localOffset + 30 + u16(lb, 26) + u16(lb, 28);
            entries.add(e);
        }
    }

    public Entry find(String name) {
        for (Entry e : entries) if (e.name.equals(name)) return e;
        return null;
    }

    public byte[] read(Entry e) throws IOException {
        byte[] comp = new byte[(int) e.compSize];
        raf.seek(e.dataOffset);
        raf.readFully(comp);
        if (e.method == 0) return comp;
        Inflater inf = new Inflater(true);
        inf.setInput(comp);
        ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.max(e.size, 64));
        byte[] buf = new byte[65536];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                o.write(buf, 0, n);
            }
        } catch (Exception ex) {
            throw new IOException("Inflate failed: " + e.name);
        } finally {
            inf.end();
        }
        return o.toByteArray();
    }

    /** Extract an entry (e.g. an inner .apk of an .apks bundle) to a file. */
    public void extract(Entry e, File out) throws IOException {
        if (e.method == 0) {
            OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            try { copyRaw(e.dataOffset, e.compSize, o); } finally { o.close(); }
            return;
        }
        // stream inflate to keep memory low
        InputStream in = new java.util.zip.InflaterInputStream(new RafStream(e.dataOffset, e.compSize), new Inflater(true), 65536);
        OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            o.close();
            in.close();
        }
    }

    private class RafStream extends InputStream {
        long pos, left;
        RafStream(long pos, long len) { this.pos = pos; this.left = len; }
        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : (b[0] & 0xff);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, left);
            raf.seek(pos);
            raf.readFully(b, off, n);
            pos += n; left -= n;
            return n;
        }
    }

    private void copyRaw(long off, long len, OutputStream o) throws IOException {
        byte[] buf = new byte[1 << 16];
        raf.seek(off);
        while (len > 0) {
            int n = (int) Math.min(buf.length, len);
            raf.readFully(buf, 0, n);
            o.write(buf, 0, n);
            len -= n;
        }
    }

    private static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String n = name.substring(9).toUpperCase();
        if (n.contains("/")) return false;
        return n.equals("MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC");
    }

    /** Write a copy of this APK with one entry replaced (deflated) and old signatures removed. */
    public void writeWithReplacement(File out, String replaceName, byte[] replaceData) throws IOException {
        writeWithChanges(out, replaceName, replaceData, null);
    }

    /** Like writeWithReplacement, and also adds new entries (name -> data) at the end. */
    public void writeWithChanges(File out, String replaceName, byte[] replaceData, java.util.Map<String, byte[]> add) throws IOException {
        java.util.Map<String, byte[]> rep = new java.util.HashMap<>();
        rep.put(replaceName, replaceData);
        writeWithChanges(out, rep, add, null);
    }

    /** Replaces several entries (names in {@code stored} are written uncompressed), adds new ones, drops old signatures. */
    public void writeWithChanges(File out, java.util.Map<String, byte[]> replace, java.util.Map<String, byte[]> add,
                                 java.util.Set<String> stored) throws IOException {
        CountingOut o = new CountingOut(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        int count = 0;
        try {
            for (Entry e : entries) {
                if (isOldSignature(e.name)) continue;
                byte[] nameBytes = e.name.getBytes("UTF-8");
                boolean repl = replace.containsKey(e.name);
                int method = e.method;
                long crc = e.crc, comp = e.compSize, size = e.size;
                byte[] newData = null;
                if (repl) {
                    byte[] replaceData = replace.get(e.name);
                    CRC32 c = new CRC32();
                    c.update(replaceData);
                    crc = c.getValue();
                    size = replaceData.length;
                    if (stored != null && stored.contains(e.name)) {
                        newData = replaceData;
                        method = 0;
                    } else {
                        Deflater d = new Deflater(9, true);
                        d.setInput(replaceData);
                        d.finish();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        while (!d.finished()) { int n = d.deflate(buf); bo.write(buf, 0, n); }
                        d.end();
                        newData = bo.toByteArray();
                        method = 8;
                    }
                    comp = newData.length;
                }
                int flags = e.flags & ~0x0008;
                long localOff = o.count;
                // uncompressed entries must be aligned: native libs to 4096 (page), others to 4
                byte[] extra = new byte[0];
                if (method == 0) {
                    int m = e.name.endsWith(".so") ? 4096 : 4;
                    long dataStart = localOff + 30 + nameBytes.length;
                    int padLen = (int) ((m - ((dataStart + 6) % m)) % m);
                    extra = new byte[6 + padLen];
                    ByteBuffer xb = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
                    xb.putShort((short) 0xD935).putShort((short) (2 + padLen)).putShort((short) m);
                }
                ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
                h.putInt(0x04034b50).putShort((short) e.versionNeeded).putShort((short) flags)
                        .putShort((short) method).putShort((short) e.time).putShort((short) e.date)
                        .putInt((int) crc).putInt((int) comp).putInt((int) size)
                        .putShort((short) nameBytes.length).putShort((short) extra.length);
                o.write(h.array());
                o.write(nameBytes);
                o.write(extra);
                if (repl) o.write(newData);
                else copyRaw(e.dataOffset, e.compSize, o);

                ByteBuffer c = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                c.putInt(0x02014b50).putShort((short) 20).putShort((short) e.versionNeeded)
                        .putShort((short) flags).putShort((short) method).putShort((short) e.time)
                        .putShort((short) e.date).putInt((int) crc).putInt((int) comp).putInt((int) size)
                        .putShort((short) nameBytes.length).putShort((short) 0).putShort((short) 0)
                        .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) localOff);
                central.write(c.array());
                central.write(nameBytes);
                count++;
            }
            if (add != null) {
                for (java.util.Map.Entry<String, byte[]> en : add.entrySet()) {
                    byte[] nameBytes = en.getKey().getBytes("UTF-8");
                    byte[] raw = en.getValue();
                    CRC32 c = new CRC32();
                    c.update(raw);
                    Deflater d = new Deflater(9, true);
                    d.setInput(raw);
                    d.finish();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    while (!d.finished()) { int n = d.deflate(buf); bo.write(buf, 0, n); }
                    d.end();
                    byte[] comp = bo.toByteArray();
                    long localOff = o.count;
                    ByteBuffer h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
                    h.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0800).putShort((short) 8)
                            .putShort((short) 0).putShort((short) 0x21).putInt((int) c.getValue())
                            .putInt(comp.length).putInt(raw.length).putShort((short) nameBytes.length).putShort((short) 0);
                    o.write(h.array());
                    o.write(nameBytes);
                    o.write(comp);
                    ByteBuffer cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                    cd.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0x0800)
                            .putShort((short) 8).putShort((short) 0).putShort((short) 0x21).putInt((int) c.getValue())
                            .putInt(comp.length).putInt(raw.length).putShort((short) nameBytes.length)
                            .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                            .putInt(0).putInt((int) localOff);
                    central.write(cd.array());
                    central.write(nameBytes);
                    count++;
                }
            }
            long cdOff = o.count;
            byte[] cdb = central.toByteArray();
            o.write(cdb);
            ByteBuffer eo = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            eo.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                    .putShort((short) count).putShort((short) count)
                    .putInt(cdb.length).putInt((int) cdOff).putShort((short) 0);
            o.write(eo.array());
        } finally {
            o.close();
        }
    }

    private static final class CountingOut extends OutputStream {
        final OutputStream o;
        long count;
        CountingOut(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); count += len; }
        @Override public void close() throws IOException { o.close(); }
    }
}
