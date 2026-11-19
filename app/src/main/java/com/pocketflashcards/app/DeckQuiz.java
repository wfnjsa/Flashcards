package com.pocketflashcards.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

final class DeckQuiz {
    static final int QUESTION_COUNT = 10;
    static final int OPTION_COUNT = 3;

    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private DeckQuiz() {}

    static List<Question> generate(List<FlashcardStore.Card> cards, Random random) {
        Map<String, Set<String>> meaningsForTerms = new HashMap<>();
        Map<String, Set<String>> termsForMeanings = new HashMap<>();
        Map<String, String> termLabels = new LinkedHashMap<>();
        Map<String, String> meaningLabels = new LinkedHashMap<>();
        Map<String, String> termReadings = new HashMap<>();
        List<FlashcardStore.Card> uniqueCards = new ArrayList<>();

        for (FlashcardStore.Card card : cards) {
            String term = answerKey(card.term);
            String meaning = answerKey(card.definition);
            if (term.isEmpty() || meaning.isEmpty()) {
                continue;
            }
            if (meaningsForTerms.computeIfAbsent(term, key -> new HashSet<>()).add(meaning)) {
                uniqueCards.add(card);
            }
            termsForMeanings.computeIfAbsent(meaning, key -> new HashSet<>()).add(term);
            termLabels.putIfAbsent(term, card.term);
            termReadings.putIfAbsent(term, card.reading);
            meaningLabels.putIfAbsent(meaning, card.definition);
        }
        if (uniqueCards.isEmpty()) {
            return Collections.emptyList();
        }

        List<FlashcardStore.Card> order = questionOrder(uniqueCards, random);
        List<Boolean> meaningFirst = new ArrayList<>(QUESTION_COUNT);
        for (int index = 0; index < QUESTION_COUNT; index++) {
            meaningFirst.add(index < QUESTION_COUNT / 2);
        }
        Collections.shuffle(meaningFirst, random);

        List<Question> questions = new ArrayList<>(QUESTION_COUNT);
        for (int index = 0; index < QUESTION_COUNT; index++) {
            FlashcardStore.Card card = order.get(index);
            boolean reverse = meaningFirst.get(index);
            String prompt = reverse ? card.definition : card.term;
            String answer = reverse ? card.term : card.definition;
            Map<String, String> labels = reverse ? termLabels : meaningLabels;
            Map<String, Set<String>> validAnswers = reverse ? termsForMeanings : meaningsForTerms;
            List<String> options = sampleDistractors(labels, validAnswers.get(answerKey(prompt)), random);
            options.add(answer);
            Collections.shuffle(options, random);
            int correct = options.indexOf(answer);
            List<String> optionReadings = new ArrayList<>(options.size());
            for (int option = 0; option < options.size(); option++) {
                optionReadings.add(!reverse ? "" : option == correct ? card.reading
                        : termReadings.getOrDefault(answerKey(options.get(option)), ""));
            }
            questions.add(new Question(prompt, options, correct, reverse,
                    reverse ? "" : card.reading, optionReadings));
        }
        return questions;
    }

    private static List<FlashcardStore.Card> questionOrder(List<FlashcardStore.Card> cards, Random random) {
        List<FlashcardStore.Card> order = new ArrayList<>(QUESTION_COUNT);
        while (order.size() < QUESTION_COUNT) {
            Collections.shuffle(cards, random);
            if (cards.size() > 1 && !order.isEmpty()
                    && cards.get(0).id == order.get(order.size() - 1).id) {
                Collections.swap(cards, 0, 1 + random.nextInt(cards.size() - 1));
            }
            int count = Math.min(QUESTION_COUNT - order.size(), cards.size());
            order.addAll(cards.subList(0, count));
        }
        return order;
    }

    private static List<String> sampleDistractors(Map<String, String> labels, Set<String> accepted,
            Random random) {
        int limit = OPTION_COUNT - 1;
        List<String> sample = new ArrayList<>(OPTION_COUNT);
        int seen = 0;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (accepted.contains(entry.getKey())) {
                continue;
            }
            seen++;
            if (sample.size() < limit) {
                sample.add(entry.getValue());
            } else {
                int replace = random.nextInt(seen);
                if (replace < limit) {
                    sample.set(replace, entry.getValue());
                }
            }
        }
        return sample;
    }

    private static String answerKey(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    static final class Question {
        final String prompt;
        final List<String> options;
        final int correctOption;
        final boolean meaningFirst;
        final String promptReading;
        final List<String> optionReadings;

        Question(String prompt, List<String> options, int correctOption, boolean meaningFirst) {
            this(prompt, options, correctOption, meaningFirst, "", null);
        }

        Question(String prompt, List<String> options, int correctOption, boolean meaningFirst,
                String promptReading, List<String> optionReadings) {
            this.promptReading = promptReading == null ? "" : promptReading;
            List<String> readings = new ArrayList<>(options.size());
            for (int index = 0; index < options.size(); index++) {
                String reading = optionReadings != null && index < optionReadings.size()
                        ? optionReadings.get(index) : "";
                readings.add(reading == null ? "" : reading);
            }
            this.optionReadings = Collections.unmodifiableList(readings);
            this.prompt = prompt;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
            this.correctOption = correctOption;
            this.meaningFirst = meaningFirst;
        }
    }
}
