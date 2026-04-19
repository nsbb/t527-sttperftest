package com.t527.wav2vecdemo.conformer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * CTC Greedy Decoder for Conformer BPE output.
 * Uses vocab_correct.json (tokenizer ID → token mapping).
 */
public class ConformerDecoder {
    private static final String TAG = "ConformerDecoder";
    private static final int BLANK_ID = 2048;

    private final Map<Integer, String> vocab = new HashMap<>();

    /**
     * Load vocab from file path (e.g. /data/local/tmp/kr_conf_sb/vocab_correct.json)
     */
    public boolean loadVocab(String jsonPath) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(jsonPath));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                vocab.put(Integer.parseInt(key), json.getString(key));
            }
            Log.d(TAG, "Loaded vocab: " + vocab.size() + " tokens");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load vocab: " + e.getMessage());
            return false;
        }
    }

    /**
     * CTC Greedy Decode: argmax IDs → text
     * 1. Collapse consecutive duplicates
     * 2. Remove blank (2048)
     * 3. Map token IDs → text using vocab
     * 4. ▁ prefix = word boundary (space)
     */
    public String decode(int[] argmaxIds) {
        if (argmaxIds == null || argmaxIds.length == 0) return "";

        // Collapse + remove blank
        List<Integer> collapsed = new ArrayList<>();
        int prev = -1;
        for (int tid : argmaxIds) {
            if (tid != prev) {
                if (tid != BLANK_ID) {
                    collapsed.add(tid);
                }
                prev = tid;
            }
        }

        // Token IDs → text
        StringBuilder sb = new StringBuilder();
        for (int tid : collapsed) {
            String token = vocab.get(tid);
            if (token == null) token = "<" + tid + ">";

            if (token.equals("<unk>")) {
                // skip unknown tokens
                continue;
            } else if (token.startsWith("\u2581")) {
                // ▁ = word boundary
                sb.append(" ").append(token.substring(1));
            } else {
                sb.append(token);
            }
        }

        String result = sb.toString().trim();

        // 후처리: 끝에 붙는 간투어 제거 ("음", "어", "네", "응", "아", "예")
        String[] fillers = {" 음", " 어", " 네", " 응", " 아", " 예", " 에"};
        for (String f : fillers) {
            if (result.endsWith(f)) {
                result = result.substring(0, result.length() - f.length()).trim();
                break;
            }
        }

        return result;
    }

    /**
     * Count blank tokens in argmax
     */
    public int countBlanks(int[] argmaxIds) {
        int count = 0;
        for (int id : argmaxIds) {
            if (id == BLANK_ID) count++;
        }
        return count;
    }
}
