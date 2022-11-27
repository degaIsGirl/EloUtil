package com.example.share;

import cn.hutool.core.util.NumberUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class EloUtil {
    private HashMap<String, BigDecimal> cacheWinRate = new HashMap<>();

    private final BigDecimal MAX_RATING = new BigDecimal("5000");

    private final Integer PROCESS_RATE = 4;

    private Map<Integer, Rating> userMap;

    private boolean isDebug = false;

    public EloUtil setDebug(boolean debug) {
        isDebug = debug;
        return this;
    }

    @Data
    private class Rating {
        private Double expectedRank;

        private Double tmpRank;

        private Integer performanceRating;

        private MatchUser matchUser;
    }

    @Data
    public static class MatchUser {
        private int userId;

        private double currentRating;

        private Integer matchRank;

        private double changeScore;

        public MatchUser(int uid, double currentRating, int matchRank) {
            this.userId = uid;
            this.currentRating = currentRating;
            this.matchRank = matchRank;
        }
    }

    EloUtil(List<MatchUser> matchUserList) {
        if (matchUserList.isEmpty()) {
            throw new IllegalArgumentException("matchUser is empty");
        }
        this.userMap = matchUserList.stream().collect(Collectors.toMap(
                MatchUser::getUserId,
                (item) -> {
                    Rating rating = new Rating();
                    rating.setMatchUser(item);
                    return rating;
                }
        ));
    }

    /**
     * 计算预期排名
     */
    private void fillExpectedRank() {
        Set<Map.Entry<Integer, Rating>> entries = userMap.entrySet();
        for (Map.Entry<Integer, Rating> entryOutter : entries) {
            BigDecimal expectedRank = new BigDecimal("1");
            for (Map.Entry<Integer, Rating> entryInner : entries) {
                if (!entryOutter.getKey().equals(entryInner.getKey())) {
                    BigDecimal loseRate = this.getLoseRate(entryOutter.getValue().getMatchUser().getCurrentRating(), entryInner.getValue().getMatchUser().getCurrentRating());
                    expectedRank = expectedRank.add(loseRate);
                    if (isDebug) {
                        log.info(
                                "[{}] 输率 {} => {}, {}",
                                "fillExpectedRank",
                                entryOutter.getKey(),
                                entryInner.getKey(),
                                loseRate
                        );
                    }
                }
            }
            entryOutter.getValue().setExpectedRank(
                    expectedRank.setScale(PROCESS_RATE, RoundingMode.HALF_DOWN).doubleValue()
            );
            if (isDebug)
                log.info("[{}] 用户 {} 预期排名 {}", "fillExpectedRank", entryOutter.getKey(), entryOutter.getValue().getExpectedRank());
        }
    }

    /**
     * 计算临时排名
     */
    private void fillTmpRank() {
        //计算临时排名
        for (Map.Entry<Integer, Rating> entry : userMap.entrySet()) {
            double sqrt = Math.sqrt(entry.getValue().getExpectedRank() * entry.getValue().getMatchUser().getMatchRank());
            entry.getValue().setTmpRank(
                    new BigDecimal(sqrt).setScale(PROCESS_RATE, RoundingMode.HALF_UP).doubleValue()
            );
            if (isDebug) log.info("[{}] 用户 {} 预期排名 {}, 真实排名{}, 协调排名{}",
                    "fillTmpRank",
                    entry.getKey(),
                    entry.getValue().getExpectedRank(),
                    entry.getValue().getMatchUser().getMatchRank(),
                    entry.getValue().getTmpRank()
            );
        }
    }

    /**
     * 计算表现分
     */
    private void fillPerformanceRating() {
        for (Map.Entry<Integer, Rating> entry : userMap.entrySet()) {

            BigDecimal top = MAX_RATING;
            BigDecimal low = new BigDecimal(0);
            BigDecimal middleScore = new BigDecimal(0);
            while (new BigDecimal("0.1").compareTo(
                    top.subtract(low)
            ) <= 0) {
                middleScore = top.subtract(low).divide(new BigDecimal("2"), PROCESS_RATE, RoundingMode.HALF_UP)
                        .add(low);
                int comResult = this.compareWithTryRating(entry.getValue().getTmpRank(), middleScore.doubleValue(), entry.getValue().getMatchUser().getUserId());
                if (comResult == 0) {
                    break;
                } else if (comResult < 0) {//分数打高了
                    top = middleScore;
                } else {//低了
                    low = middleScore;
                }

                if (isDebug)
                    log.info("[{}] 用户 {}, 二分查找表现分, 上限: {}, 下限: {}, 中位数: {}, 高还是低: {}",
                            "fillPerformanceRating",
                            entry.getValue().getMatchUser().getUserId(),
                            top,
                            low,
                            middleScore,
                            comResult < 0 ? "高了" : "低了"
                    );
            }
            if (isDebug)
                log.info("[{}] 用户 {}, 二分查找结束, 结果为: {}",
                        "fillPerformanceRating",
                        entry.getValue().getMatchUser().getUserId(),
                        middleScore.intValue()
                );
            entry.getValue().setPerformanceRating(middleScore.intValue());
        }
    }

    /**
     * 填充表现分数
     */
    private void fillNewRatingScore() {
        for (Map.Entry<Integer, Rating> entry : userMap.entrySet()) {
            entry.getValue().getMatchUser().setChangeScore(
                    (entry.getValue().getPerformanceRating() - entry.getValue().getMatchUser().getCurrentRating()) / 2
            );
            entry.getValue().getMatchUser().setCurrentRating(
                    entry.getValue().getMatchUser().getCurrentRating() + entry.getValue().getMatchUser().getChangeScore()
            );
            if (isDebug)
                log.info("[{}] 用户 {}, 变化分 {}, 最新分数 {}",
                        "fillNewRatingScore",
                        entry.getValue().getMatchUser().getUserId(),
                        entry.getValue().getMatchUser().getChangeScore(),
                        entry.getValue().getMatchUser().getCurrentRating()
                );
        }
    }

    public synchronized Map<Integer, MatchUser> calRating() {
        // 计算出预期排名
        this.fillExpectedRank();

        // 协调排名
        this.fillTmpRank();

        //计算表现分
        this.fillPerformanceRating();

        //计算应
        this.fillNewRatingScore();

        return this.userMap.entrySet().stream().collect(
                Collectors.toMap(
                        (item) -> item.getValue().getMatchUser().getUserId(),
                        (item) -> item.getValue().getMatchUser()
                )
        );
    }

    /**
     * 算出胜率
     *
     * @param ratingA
     * @param ratingB
     * @return
     */
    private BigDecimal getWinRate(double ratingA, double ratingB) {
        BigDecimal bigDecimalA = new BigDecimal(ratingA + "");
        BigDecimal bigDecimalB = new BigDecimal(ratingB + "");

        String keyName = bigDecimalA + "_" + bigDecimalB;
        String flapKeyName = bigDecimalB + "_" + bigDecimalA;
        if (this.cacheWinRate.containsKey(keyName)) {
            return this.cacheWinRate.get(keyName);
        } else {
            BigDecimal winRate = new BigDecimal(
                    1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0))
            );
            winRate = winRate.setScale(PROCESS_RATE, RoundingMode.HALF_DOWN);
            this.cacheWinRate.put(keyName, winRate);
            this.cacheWinRate.put(flapKeyName,
                    NumberUtil.sub(1, winRate)
            );
            return winRate;
        }
    }

    /**
     * 败率
     *
     * @param ratingA
     * @param ratingB
     * @return
     */
    private BigDecimal getLoseRate(double ratingA, double ratingB) {
        BigDecimal winRate = this.getWinRate(ratingA, ratingB);
        return new BigDecimal(1).subtract(winRate).setScale(PROCESS_RATE, RoundingMode.HALF_UP);
    }

    /**
     * @param tmpRank
     * @param tryRating
     * @param uid
     * @return
     */
    private int compareWithTryRating(Double tmpRank, Double tryRating, Integer uid) {
        BigDecimal expectedRank = new BigDecimal(1);
        for (Map.Entry<Integer, Rating> entry : userMap.entrySet()) {
            if (entry.getValue().getMatchUser().getUserId() != uid) {
                expectedRank = expectedRank.add(this.getWinRate(entry.getValue().getMatchUser().getCurrentRating(), tryRating));
            }
        }
        // -1 => 分高了
        // 1  => 低了
        return expectedRank.compareTo(new BigDecimal(
                tmpRank)
        );
    }
}
