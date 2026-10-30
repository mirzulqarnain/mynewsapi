import java.util.*;

/** Simple extractive summarizer: scores sentences by word frequency, keeps the top N in original order. */
public class Summarizer {

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "that", "with", "this", "from", "have", "has", "had", "was", "were", "are",
            "but", "not", "his", "her", "its", "their", "they", "them", "she", "him", "will", "would", "can",
            "could", "said", "also", "been", "than", "then", "into", "about", "after", "before", "which",
            "who", "whom", "what", "when", "where", "while", "there", "these", "those", "over", "such",
            "more", "most", "other", "some", "any", "all", "our", "your", "you", "out", "one", "two", "may",
            "per", "did", "does", "being", "because", "during", "between", "under", "just", "like"));

    public static String summarize(String text, int maxSentences) {
        String[] raw = text.replaceAll("\\s+", " ").trim().split("(?<=[.!?])\\s+");
        List<String> sentences = new ArrayList<>();
        for (String s : raw) {
            if (s.length() >= 30) sentences.add(s.trim());
        }
        if (sentences.size() <= maxSentences) return String.join(" ", sentences);

        Map<String, Integer> freq = new HashMap<>();
        for (String s : sentences) {
            for (String w : words(s)) freq.merge(w, 1, Integer::sum);
        }

        double[] score = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            List<String> ws = words(sentences.get(i));
            if (ws.isEmpty()) continue;
            double sum = 0;
            for (String w : ws) sum += freq.get(w);
            score[i] = sum / ws.size();
            if (i == 0) score[i] *= 1.2; // lead sentence bonus
        }

        Integer[] idx = new Integer[sentences.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        List<Integer> top = new ArrayList<>(Arrays.asList(idx).subList(0, maxSentences));
        Collections.sort(top);

        StringBuilder sb = new StringBuilder();
        for (int i : top) sb.append(sentences.get(i)).append(' ');
        return sb.toString().trim();
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase().split("[^a-z']+")) {
            if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }
}
