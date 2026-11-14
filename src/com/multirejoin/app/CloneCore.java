package com.multirejoin.app;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;

/** Pure-Java part of cloning: rename package inside one APK file and re-sign it. */
public final class CloneCore {
    /** Also make the clone open nicely in small freeform windows (see ManifestWindowPatch). */
    public boolean windowFriendly = true;
    /** Our floating-window code (assets/float.dex). When set, clones become floating windows. */
    public byte[] floatDex = null;
    /** Window slot for this clone (0 = first spot). */
    public int floatSlot = 0;

    /** Makes the replacement icon picture for one file (same size/format as the original). */
    public interface IconMaker { byte[] make(String path, byte[] original); }
    /** When set, the clone's launcher icon pictures are replaced. */
    public IconMaker iconMaker = null;
    /** Result of the last clone: how many icon pictures were replaced. */
    public int iconsReplaced = 0;
    private final PrivateKey key;
    private final X509Certificate cert;

    public CloneCore(InputStream pk8, InputStream certDer) throws Exception {
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(pk8)));
        cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certDer);
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    public static final class Info {
        public String pkg;
        public boolean split;
        public int minSdk;
    }

    public static Info info(File apk) throws Exception {
        ApkZip z = new ApkZip(apk);
        try {
            ApkZip.Entry m = z.find("AndroidManifest.xml");
            if (m == null) throw new Exception("No AndroidManifest.xml in " + apk.getName());
            Axml a = new Axml(z.read(m));
            Info i = new Info();
            i.pkg = a.getPackage();
            i.split = a.isSplit();
            i.minSdk = a.getMinSdk();
            return i;
        } finally {
            z.close();
        }
    }

    /** in -> out: package renamed oldPkg -> newPkg, then signed. tmp is a scratch file. */
    public void cloneApk(File in, File tmp, File out, String oldPkg, String newPkg) throws Exception {
        cloneApk(in, tmp, out, oldPkg, newPkg, null);
    }

    public void cloneApk(File in, File tmp, File out, String oldPkg, String newPkg, String label) throws Exception {
        ApkZip z = new ApkZip(in);
        int minSdk;
        try {
            ApkZip.Entry m = z.find("AndroidManifest.xml");
            if (m == null) throw new Exception("No AndroidManifest.xml in " + in.getName());
            Axml a = new Axml(z.read(m));
            minSdk = a.getMinSdk();
            byte[] patched = a.renamePackage(oldPkg, newPkg, label);
            java.util.Map<String, byte[]> add = null;
            if (floatDex != null && !a.isSplit()) {
                byte[] f = ManifestWindowPatch.patchFloat(patched, newPkg, floatSlot);
                if (f == null) throw new Exception("Couldn't add the floating window to this app");
                patched = f;
                // next free classesN.dex (classes.dex, classes2.dex, ...)
                int n = 1;
                while (z.find(n == 1 ? "classes.dex" : "classes" + n + ".dex") != null) n++;
                add = new java.util.LinkedHashMap<>();
                add.put(n == 1 ? "classes.dex" : "classes" + n + ".dex", floatDex);
            } else if (windowFriendly && !a.isSplit()) {
                byte[] w = ManifestWindowPatch.patch(patched, 320, 180, 100);
                if (w != null) patched = w;
            }
            java.util.Map<String, byte[]> rep = new java.util.HashMap<>();
            rep.put("AndroidManifest.xml", patched);
            java.util.Set<String> stored = new java.util.HashSet<>();
            iconsReplaced = 0;
            if (iconMaker != null && !a.isSplit()) {
                try {
                    ApkZip.Entry res = z.find("resources.arsc");
                    int[] refs = a.iconRefs();
                    if (res != null && refs[0] != 0) {
                        ArscIcon ai = ArscIcon.prepare(z.read(res), refs[0], refs[1]);
                        if (ai != null) {
                            java.util.Map<String, byte[]> pics = new java.util.HashMap<>();
                            for (String path : ai.pictures) {
                                ApkZip.Entry pe = z.find(path);
                                if (pe == null) continue;
                                byte[] np = iconMaker.make(path, z.read(pe));
                                if (np != null) pics.put(path, np);
                            }
                            if (!pics.isEmpty()) {
                                rep.putAll(pics);
                                rep.put("resources.arsc", ai.arsc);
                                stored.add("resources.arsc");     // Android 11+ needs it uncompressed
                                iconsReplaced = pics.size();
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // icon is cosmetic: keep the original if anything goes wrong
                }
            }
            z.writeWithChanges(tmp, rep, add, stored);
        } finally {
            z.close();
        }
        sign(tmp, out, minSdk);
        tmp.delete();
    }

    /** Sign an APK as-is (used for originals that only need re-signing — not normally needed). */
    public void sign(File in, File out, int minSdk) throws Exception {
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "CERT", key, Collections.singletonList(cert)).build();
        ApkSigner.Builder b = new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setCreatedBy("MultiRejoin")
                .setV1SigningEnabled(minSdk < 24)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true);
        if (minSdk < 24) b.setMinSdkVersion(Math.max(minSdk, 1));
        b.build().sign();
    }
}
