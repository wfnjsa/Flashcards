package com.pocketflashcards.app;

final class ReviewSchedule {
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static final int[] INTERVALS = {1, 3, 7, 14, 30};

    final int streak;
    final long dueAt;

    private ReviewSchedule(int streak, long dueAt) {
        this.streak = streak;
        this.dueAt = dueAt;
    }

    static ReviewSchedule afterAnswer(int streak, boolean remembered, long now) {
        if (!remembered) {
            return new ReviewSchedule(0, now + 10 * 60 * 1000L);
        }
        int intervalIndex = Math.min(streak, INTERVALS.length - 1);
        int nextStreak = Math.min(streak + 1, INTERVALS.length);
        return new ReviewSchedule(nextStreak, now + INTERVALS[intervalIndex] * DAY);
    }
}
