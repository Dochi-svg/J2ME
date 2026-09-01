package ru.playsoftware.j2meloader.util;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TranslationManager - Fokus pada translate teks via API
 * Bekerja sama dengan Graphics.java (ACUAN) yang menangani rendering & dump
 * 
 * TUGAS TranslationManager:
 * - Auto-translate teks via Google Translate API
 * - Menyediakan hasil translate ke Graphics.java
 * - TIDAK melakukan rendering
 * - TIDAK melakukan dump (Graphics yang handle)
 */
public class TranslationManager {
    
    // Map untuk menyimpan hasil translate
    private static final Map<String, String> translationMap = new ConcurrentHashMap<>();
    
    // Set untuk menandai teks yang sedang di-translate (hindari duplicate request)
    private static final Map<String, Boolean> sedangDiterjemahkan = new ConcurrentHashMap<>();
    
    // Executor untuk translate di background
    private static ExecutorService translateExecutor;
    
    // Flag untuk auto-translate
    private static volatile boolean autoTranslateEnabled = true;
    
    /**
     * Inisialisasi TranslationManager
     * Dipanggil dari Graphics.java atau Activity saat aplikasi mulai
     */
    public static synchronized void init() {
        if (translateExecutor == null || translateExecutor.isShutdown()) {
            translateExecutor = Executors.newFixedThreadPool(4);
        }
    }
    
    /**
     * Shutdown executor
     */
    public static synchronized void shutdown() {
        if (translateExecutor != null && !translateExecutor.isShutdown()) {
            translateExecutor.shutdown();
            translateExecutor = null;
        }
    }
    
    /**
     * Proses string untuk translate
     * 
     * ALUR:
     * 1. Cek apakah sudah ada di translationMap
     * 2. Jika belum, dan autoTranslate aktif, translate via API di background
     * 3. Return original jika belum ada terjemahan
     * 
     * @param original Teks asli dari game
     * @return Teks yang sudah di-translate (atau original jika belum ada)
     */
    public static String processString(String original) {
        if (original == null) return original;
        
        String trimmed = original.trim();
        
        // Skip jika terlalu pendek atau hanya angka
        if (trimmed.isEmpty() || trimmed.length() <= 1 || trimmed.matches("^\\d+$")) {
            return original;
        }
        
        // ==== 1. CEK TRANSLATION MAP ====
        if (translationMap.containsKey(trimmed)) {
            // Sudah ada terjemahan, return hasil translate
            return original.replace(trimmed, translationMap.get(trimmed));
        }
        
        // ==== 2. AUTO-TRANSLATE DI BACKGROUND ====
        if (autoTranslateEnabled && !sedangDiterjemahkan.containsKey(trimmed)) {
            sedangDiterjemahkan.put(trimmed, Boolean.TRUE);
            
            if (translateExecutor != null && !translateExecutor.isShutdown()) {
                translateExecutor.execute(() -> terjemahkanViaAPI(trimmed));
            }
        }
        
        // ==== 3. RETURN ORIGINAL (belum ada terjemahan) ====
        return original;
    }
    
    /**
     * Cek apakah teks sudah di-translate
     * Digunakan oleh Graphics.java
     */
    public static boolean hasTranslation(String text) {
        if (text == null) return false;
        return translationMap.containsKey(text.trim());
    }
    
    /**
     * Dapatkan terjemahan jika ada
     * Digunakan oleh Graphics.java
     */
    public static String getTranslation(String text) {
        if (text == null) return text;
        String trimmed = text.trim();
        return translationMap.getOrDefault(trimmed, text);
    }
    
    /**
     * Tambahkan terjemahan manual
     * Bisa dipanggil dari Graphics.java saat load translation file
     */
    public static void addTranslation(String original, String translated) {
        if (original != null && translated != null) {
            translationMap.put(original.trim(), translated);
        }
    }
    
    /**
     * Cek apakah teks mengandung karakter Chinese
     * Digunakan oleh Graphics.java untuk menentukan rendering khusus
     */
    public static boolean containsChinese(String text) {
        if (text == null) return false;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Translate teks via Google Translate API
     * Dijalankan di background thread
     */
    private static void terjemahkanViaAPI(String teks) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=id&dt=t&q=" 
                    + URLEncoder.encode(teks, "UTF-8");
            
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    
                    JSONArray jsonArray = new JSONArray(response.toString());
                    if (jsonArray.length() > 0 && !jsonArray.isNull(0)) {
                        JSONArray sentences = jsonArray.getJSONArray(0);
                        StringBuilder translatedResult = new StringBuilder();
                        
                        for (int i = 0; i < sentences.length(); i++) {
                            if (!sentences.isNull(i)) {
                                JSONArray sentence = sentences.getJSONArray(i);
                                if (!sentence.isNull(0)) {
                                    translatedResult.append(sentence.getString(0));
                                }
                            }
                        }
                        
                        String hasilTranslate = translatedResult.toString().trim();
                        
                        if (!hasilTranslate.isEmpty() && !hasilTranslate.equals(teks)) {
                            // Simpan hasil translate
                            translationMap.put(teks, hasilTranslate);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silent fail - tidak perlu log
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            sedangDiterjemahkan.remove(teks);
        }
    }
    
    /**
     * Clear semua translation
     */
    public static void clearTranslations() {
        translationMap.clear();
        sedangDiterjemahkan.clear();
    }
    
    /**
     * Dapatkan jumlah translation yang sudah ada
     */
    public static int getTranslationCount() {
        return translationMap.size();
    }
    
    // ==== SETTER METHODS ====
    
    public static void setAutoTranslateEnabled(boolean enabled) { 
        autoTranslateEnabled = enabled; 
    }
    
    public static boolean isAutoTranslateEnabled() { 
        return autoTranslateEnabled; 
    }
}
