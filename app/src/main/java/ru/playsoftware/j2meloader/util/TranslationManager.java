package ru.playsoftware.j2meloader.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TranslationManager {
    // Memory Cache
    private static final Map<String, String> translationMap = new ConcurrentHashMap<>();
    private static final Map<String, String> reverseTranslationMap = new ConcurrentHashMap<>();
    private static final Map<String, String> dumpedStrings = new ConcurrentHashMap<>();
    
    // State Tracking dari logika JS: sedangDiterjemahkan & isOfflineMode
    private static final Map<String, Boolean> sedangDiterjemahkan = new ConcurrentHashMap<>();
    private static final AtomicBoolean isOfflineMode = new AtomicBoolean(false);
    
    private static File translationFile;
    private static File dumpFile;
    
    private static boolean isDumpMode = true; 
    private static boolean autoTranslateEnabled = true;
    
    private static final AtomicBoolean hasNewDataToSave = new AtomicBoolean(false);
    private static ScheduledExecutorService saveScheduler;
    private static final ExecutorService translateExecutor = Executors.newFixedThreadPool(3);

    public static void init(File gameDir) {
        if (gameDir == null) return;
        
        translationFile = new File(gameDir, "translation.json");
        dumpFile = new File(gameDir, "dump.json");
        
        loadTranslation();
        loadExistingDump();

        // Menyimpan dump.json secara teratur (Interval 500ms sesuai referensi window.__dumpTimer)
        if (isDumpMode && saveScheduler == null) {
            saveScheduler = Executors.newSingleThreadScheduledExecutor();
            saveScheduler.scheduleWithFixedDelay(TranslationManager::saveDumpInternal, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    public static void loadTranslation() {
        translationMap.clear();
        reverseTranslationMap.clear();

        if (translationFile == null || !translationFile.exists()) return;

        try (FileReader reader = new FileReader(translationFile)) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            if (sb.length() == 0) return;
            
            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String val = json.getString(key);
                translationMap.put(key, val);
                reverseTranslationMap.put(val, key);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadExistingDump() {
        if (dumpFile == null || !dumpFile.exists()) return;
        try (FileReader reader = new FileReader(dumpFile)) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                sb.append((char) ch);
            }
            if (sb.length() == 0) return;

            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                dumpedStrings.put(key, json.getString(key));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // saveDumpInternal menggunakan konsep atomic swap (.tmp) agar data tidak corrupt saat dipaksa save
    public static void saveDumpInternal() {
        if (!isDumpMode || !hasNewDataToSave.compareAndSet(true, false) || dumpFile == null) return;
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : dumpedStrings.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }

            File tempFile = new File(dumpFile.getAbsolutePath() + ".tmp");
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(json.toString(4));
            }
            
            if (tempFile.exists()) {
                if (dumpFile.exists()) dumpFile.delete();
                tempFile.renameTo(dumpFile);
            }
        } catch (Exception e) {
            hasNewDataToSave.set(true);
            e.printStackTrace();
        }
    }

    public static void shutdownScheduler() {
        saveDumpInternal(); // Eksekusi mirip event beforeunload / visibilitychange
        if (saveScheduler != null && !saveScheduler.isShutdown()) {
            saveScheduler.shutdownNow();
            saveScheduler = null;
        }
        if (translateExecutor != null && !translateExecutor.isShutdown()) {
            translateExecutor.shutdownNow();
        }
    }

    // Logika Pemrosesan Teks (Pola dari `prosesText` JS)
    public static String processString(String original) {
        if (original == null) return original;
        
        // Filter teks kosong, 1 karakter, atau angka murni
        if (original.isEmpty() || original.length() <= 1 || original.matches("^\\d+$")) {
            return original;
        }

        // Tier 1: Cek apakah sudah ada di kamus
        if (translationMap.containsKey(original)) {
            if (dumpedStrings.containsKey(original)) {
                dumpedStrings.remove(original);
                hasNewDataToSave.set(true);
            }
            return translationMap.get(original);
        }

        // Tier 2: Cegah dump jika string ini adalah teks hasil terjemahan (reverse check)
        if (reverseTranslationMap.containsKey(original)) {
            return original;
        }

        // Tier 3: Trigger API Translate jika belum ada di kamus & tidak offline
        if (autoTranslateEnabled && !isOfflineMode.get() && !sedangDiterjemahkan.containsKey(original)) {
            sedangDiterjemahkan.put(original, true);
            translateExecutor.execute(() -> terjemahkanViaAPI(original));
        }

        // Tier 4: Penyaringan Subtext & Dump teks mentah
        if (isDumpMode) {
            // Hapus substring yang lebih pendek dari daftar dump jika kalimat yang lebih panjang muncul
            for (String key : dumpedStrings.keySet()) {
                if (original.length() > key.length() && original.contains(key)) {
                    dumpedStrings.remove(key);
                    hasNewDataToSave.set(true);
                }
            }
            
            // Cek apakah teks ini merupakan bagian pecahan dari kalimat panjang yang sudah di-dump
            boolean isSubText = false;
            for (String key : dumpedStrings.keySet()) {
                if (key.length() >= original.length() && key.contains(original)) {
                    isSubText = true;
                    break;
                }
            }
            
            if (!isSubText && !dumpedStrings.containsKey(original)) {
                dumpedStrings.put(original, original);
                hasNewDataToSave.set(true);
            }
        }

        return original;
    }

    // Implementasi `terjemahkanViaAPI` (Adaptasi HTTP Async dari JS)
    private static void terjemahkanViaAPI(String teks) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=id&dt=t&q=" 
                    + URLEncoder.encode(teks, "UTF-8");
            
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parsing array bertingkat `data[0][0][0]` dari response Google GTX
                JSONArray jsonArray = new JSONArray(response.toString());
                if (jsonArray.length() > 0) {
                    JSONArray sentences = jsonArray.getJSONArray(0);
                    StringBuilder translatedResult = new StringBuilder();

                    for (int i = 0; i < sentences.length(); i++) {
                        JSONArray sentence = sentences.getJSONArray(i);
                        translatedResult.append(sentence.getString(0));
                    }

                    String hasilTranslate = translatedResult.toString();

                    if (!hasilTranslate.isEmpty() && !hasilTranslate.equals(teks)) {
                        translationMap.put(teks, hasilTranslate);
                        reverseTranslationMap.put(hasilTranslate, teks);

                        // Bersihkan dari dump secara otomatis setelah sukses diterjemahkan
                        if (dumpedStrings.containsKey(teks)) {
                            dumpedStrings.remove(teks);
                            hasNewDataToSave.set(true);
                        }
                    }
                }
            } else {
                // Jika server merespons non-200, set sementara ke offline mode
                isOfflineMode.set(true);
            }
        } catch (Exception e) {
            // Analog catch(err): Switch ke offline mode jika tidak ada koneksi
            isOfflineMode.set(true);
        } finally {
            // Analog delete sedangDiterjemahkan[teks]
            sedangDiterjemahkan.remove(teks);
        }
    }
}
