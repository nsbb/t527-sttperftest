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

    // ===== CTC Beam Search (Phase 1c) =====
    public static final int SEQ_OUT_CHUNK = 76;
    public static final int VOCAB_SIZE = 2049;
    public static final float NEG_INF = -1e30f;
    public static final int DEFAULT_TOPK_PRUNE = 32;

    /** Beam state: a non-blank prefix + (logP_blank, logP_nonBlank). */
    private static final class Beam {
        final int[] prefix;
        float pB;   // log prob path ends at blank
        float pNB;  // log prob path ends at last non-blank token

        Beam(int[] prefix, float pB, float pNB) {
            this.prefix = prefix;
            this.pB = pB;
            this.pNB = pNB;
        }

        float total() { return logSumExp(pB, pNB); }
    }

    private static float logSumExp(float a, float b) {
        if (a <= NEG_INF + 1f) return b;
        if (b <= NEG_INF + 1f) return a;
        float m = a > b ? a : b;
        return m + (float) Math.log1p(Math.exp(-Math.abs(a - b)));
    }

    /** Wraps int[] for use as HashMap key. */
    private static final class PrefixKey {
        final int[] arr;
        final int hash;
        PrefixKey(int[] arr) { this.arr = arr; this.hash = java.util.Arrays.hashCode(arr); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PrefixKey)) return false;
            return java.util.Arrays.equals(arr, ((PrefixKey) o).arr);
        }
    }

    /**
     * CTC Prefix Beam Search (Graves 2006) with per-step top-K vocab pruning.
     *
     * @param logits  float[T * V], time-major. Treated as log-probabilities (model output is log-softmax).
     * @param T       number of time steps (after merge)
     * @param V       vocab size (must be VOCAB_SIZE = 2049)
     * @param beamWidth  number of beams to keep per step (1 ≈ greedy)
     * @param topK    per-step vocab pruning size (ignored if 0; recommended 16~64)
     * @return final argmax-style int[] sequence (best prefix); pass to decode() to get text
     */
    public int[] beamSearch(float[] logits, int T, int V, int beamWidth, int topK) {
        if (logits == null || T <= 0 || V <= 0) return new int[0];
        if (topK <= 0 || topK > V) topK = V;

        java.util.HashMap<PrefixKey, Beam> beams = new java.util.HashMap<>(beamWidth * 4);
        Beam initial = new Beam(new int[0], 0f, NEG_INF);
        beams.put(new PrefixKey(initial.prefix), initial);

        // Reusable scratch
        int[] topIdx = new int[topK];
        float[] topVal = new float[topK];

        for (int t = 0; t < T; t++) {
            int base = t * V;

            // Build top-K vocab indices (descending by logits[t]).
            // Simple selection: maintain min-heap of size topK.
            // For modest topK (<=64) and V=2049, a partial sort is cheap.
            for (int k = 0; k < topK; k++) topVal[k] = NEG_INF;
            for (int v = 0; v < V; v++) {
                float val = logits[base + v];
                if (val > topVal[topK - 1]) {
                    int j = topK - 1;
                    while (j > 0 && topVal[j - 1] < val) {
                        topVal[j] = topVal[j - 1];
                        topIdx[j] = topIdx[j - 1];
                        j--;
                    }
                    topVal[j] = val;
                    topIdx[j] = v;
                }
            }

            float lpBlank = logits[base + BLANK_ID];
            java.util.HashMap<PrefixKey, Beam> next = new java.util.HashMap<>(beams.size() * 4);

            for (Beam b : beams.values()) {
                float total = b.total();
                int last = b.prefix.length > 0 ? b.prefix[b.prefix.length - 1] : -1;

                // (1) Extend with blank → same prefix, only pB updates.
                PrefixKey kSame = new PrefixKey(b.prefix);
                Beam sb = next.get(kSame);
                float newPB = total + lpBlank;
                if (sb == null) {
                    sb = new Beam(b.prefix, newPB, NEG_INF);
                    next.put(kSame, sb);
                } else {
                    sb.pB = logSumExp(sb.pB, newPB);
                }

                // (2) Extend with each top-K token v.
                for (int k = 0; k < topK; k++) {
                    int v = topIdx[k];
                    float lpV = topVal[k];
                    if (v == BLANK_ID) continue;

                    if (v == last) {
                        // (a) Same prefix, but pNB grows from pNB only (cannot follow blank w/o repeat).
                        sb.pNB = logSumExp(sb.pNB, b.pNB + lpV);

                        // (b) New extended prefix prefix+v: from pB only (need blank between repeats).
                        int[] np = new int[b.prefix.length + 1];
                        System.arraycopy(b.prefix, 0, np, 0, b.prefix.length);
                        np[b.prefix.length] = v;
                        PrefixKey kNew = new PrefixKey(np);
                        Beam nb = next.get(kNew);
                        float newPNB = b.pB + lpV;
                        if (nb == null) {
                            next.put(kNew, new Beam(np, NEG_INF, newPNB));
                        } else {
                            nb.pNB = logSumExp(nb.pNB, newPNB);
                        }
                    } else {
                        // New token: extends from total = pB + pNB.
                        int[] np = new int[b.prefix.length + 1];
                        System.arraycopy(b.prefix, 0, np, 0, b.prefix.length);
                        np[b.prefix.length] = v;
                        PrefixKey kNew = new PrefixKey(np);
                        Beam nb = next.get(kNew);
                        float newPNB = total + lpV;
                        if (nb == null) {
                            next.put(kNew, new Beam(np, NEG_INF, newPNB));
                        } else {
                            nb.pNB = logSumExp(nb.pNB, newPNB);
                        }
                    }
                }
            }

            // Beam pruning: keep top beamWidth by total log-prob.
            if (next.size() <= beamWidth) {
                beams = next;
            } else {
                Beam[] arr = next.values().toArray(new Beam[0]);
                java.util.Arrays.sort(arr, (x, y) -> Float.compare(y.total(), x.total()));
                beams = new java.util.HashMap<>(beamWidth * 2);
                for (int i = 0; i < beamWidth; i++) {
                    beams.put(new PrefixKey(arr[i].prefix), arr[i]);
                }
            }
        }

        // Pick the best final prefix.
        Beam best = null;
        for (Beam b : beams.values()) {
            if (best == null || b.total() > best.total()) best = b;
        }
        return best != null ? best.prefix : new int[0];
    }

    /**
     * Decode token IDs (output of beamSearch — already collapsed and blank-free) to text.
     * Reuses vocab mapping + filler post-processing from greedy decode().
     */
    public String decodeFromBeam(int[] tokenIds) {
        if (tokenIds == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int tid : tokenIds) {
            String token = vocab.get(tid);
            if (token == null) token = "<" + tid + ">";
            if (token.equals("<unk>")) continue;
            else if (token.startsWith("▁"))
                sb.append(" ").append(token.substring(1));
            else
                sb.append(token);
        }
        String result = sb.toString().trim();
        String[] fillers = {" 음", " 어", " 네", " 응", " 아", " 예", " 에"};
        for (String f : fillers) {
            if (result.endsWith(f)) {
                result = result.substring(0, result.length() - f.length()).trim();
                break;
            }
        }
        return result;
    }
}
