package ch.uzh.ifi.seal.changeadvisor.ml;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;

public class Corpus implements Iterable<List<String>> {

    private List<String> originalSentences;

    private List<List<String>> documents;

    public Corpus(List<String> originalSentences, List<List<String>> documents) {
        this.originalSentences = originalSentences;
        this.documents = documents;
    }

    public void addDocument(String sentence, List<String> tokens) {
        originalSentences.add(sentence);
        documents.add(tokens);
    }

    public List<List<String>> getDocuments() {
        return documents;
    }

    public String getSentence(int i) {
        return originalSentences.get(i);
    }

    public int size() {
        return documents.size();
    }

    @Override
    public String toString() {
        return "Corpus{" +
                "documents=" + documents +
                '}';
    }

    @NotNull
    @Override
    public Iterator<List<String>> iterator() {
        return documents.iterator();
    }
}
