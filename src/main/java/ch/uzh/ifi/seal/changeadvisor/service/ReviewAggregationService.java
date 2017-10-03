package ch.uzh.ifi.seal.changeadvisor.service;


import ch.uzh.ifi.seal.changeadvisor.batch.job.ardoc.ArdocResult;
import ch.uzh.ifi.seal.changeadvisor.batch.job.reviews.Review;
import ch.uzh.ifi.seal.changeadvisor.batch.job.reviews.ReviewRepository;
import ch.uzh.ifi.seal.changeadvisor.tfidf.TfidfService;
import ch.uzh.ifi.seal.changeadvisor.web.dto.ReviewCategory;
import ch.uzh.ifi.seal.changeadvisor.web.dto.ReviewDistributionReport;
import ch.uzh.ifi.seal.changeadvisor.web.dto.ReviewsByTopLabelsDto;
import ch.uzh.ifi.seal.changeadvisor.web.util.AbstractNGram;
import ch.uzh.ifi.seal.changeadvisor.web.util.Corpus;
import ch.uzh.ifi.seal.changeadvisor.web.util.Document;
import ch.uzh.ifi.seal.changeadvisor.web.util.TfidfToken;
import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReviewAggregationService {

    private static final Logger logger = Logger.getLogger(ReviewAggregationService.class);

    private final MongoTemplate mongoOperations;

    private final TfidfService tfidfService;

    private final ReviewRepository repository;

    @Autowired
    public ReviewAggregationService(MongoTemplate mongoOperations, TfidfService tfidfService, ReviewRepository repository) {
        this.mongoOperations = mongoOperations;
        this.tfidfService = tfidfService;
        this.repository = repository;
    }

    public ReviewDistributionReport groupByCategories(final String appName) {
        TypedAggregation<ArdocResult> categoryAggregation = Aggregation.newAggregation(ArdocResult.class,
                Aggregation.match(Criteria.where("appName").is(appName)),
                Aggregation.group("category").first("category").as("category") // set group by field and save it as 'category' in resulting object.
                        .push("$$ROOT").as("reviews") // push entire document to field 'reviews' in ReviewCategory.
        );

        AggregationResults<ReviewCategory> groupResults =
                mongoOperations.aggregate(categoryAggregation, ArdocResult.class, ReviewCategory.class);

        return new ReviewDistributionReport(groupResults.getMappedResults());
    }

    /**
     * Retrieves the reviews based on the top N labels.
     * Fetches all reviews which contain these top labels.
     *
     * @param dto object representing the parameters we use to compute the top N labels
     *            (e.g. how many labels and for which app)
     * @return
     */
    public List<LabelWithReviews> reviewsByTopNLabels(ReviewsByTopLabelsDto dto) {

        List<LabelWithReviews> labelWithReviews = new ArrayList<>();
        if (dto.getNgrams() == 1) {
            List<TfidfToken<String>> labels = topNLabels(dto)
                    .stream()
                    .map(token -> (TfidfToken<String>) token).collect(Collectors.toList());

            for (TfidfToken<String> label : labels) {
                List<Review> reviews = repository.findByAppNameAndReviewTextContainingIgnoreCase(dto.getApp(), label.getToken());
                labelWithReviews.add(new LabelWithReviews(label.getToken(), reviews));
            }
        } else {
            List<TfidfToken<List<String>>> labels = topNLabels(dto)
                    .stream()
                    .map(token -> (TfidfToken<List<String>>) token).collect(Collectors.toList());

            for (TfidfToken<List<String>> label : labels) {
                final String labelString = String.join(" ", label.getToken());
                List<Review> reviews = repository.findByAppNameAndReviewTextContainingIgnoreCase(dto.getApp(), labelString);
                labelWithReviews.add(new LabelWithReviews(labelString, reviews));
            }
        }

        return labelWithReviews;
    }

    /**
     * Retrieves the top N labels for a set of reviews.
     * A label is an Ngram of tokens that are representative for a group of reviews.
     *
     * @param dto object representing the parameters we use to compute the top N labels
     *            (e.g. how many labels and for which app)
     * @return list of labels with their tfidf score.
     * @see ReviewsByTopLabelsDto
     */
    public List<TfidfToken> topNLabels(ReviewsByTopLabelsDto dto) {
        ReviewDistributionReport reviewsByCategory = groupByCategories(dto.getApp());
        final String category = dto.getCategory();
        Assert.isTrue(reviewsByCategory.hasCategory(category), String.format("Unknown category %s", category));

        List<TfidfToken> tokensWithScore = getNgramTokensWithScore(reviewsByCategory, category, dto.getNgrams());

        Collections.sort(tokensWithScore, Collections.reverseOrder());
        logger.info(tokensWithScore.size());
        final int limit = dto.getLimit();
        if (!dto.hasLimit() || limit >= tokensWithScore.size()) {
            return tokensWithScore;
        }
        return tokensWithScore.subList(0, limit);
    }

    private List<TfidfToken> getNgramTokensWithScore(ReviewDistributionReport reviewsByCategory, final String category, final int ngramSize) {
        Map<String, Document> categoryDocumentMap = mapReviewsToDocuments(reviewsByCategory, ngramSize);

        Corpus corpus = new Corpus(categoryDocumentMap.values());
        Document document = categoryDocumentMap.get(category);
        List<? extends AbstractNGram> uniqueTokens = document.uniqueTokens();

        return tfidfService.computeTfidfScoreForTokens(uniqueTokens, document, corpus);
    }

    private Map<String, Document> mapReviewsToDocuments(ReviewDistributionReport reviewDistribution, final int ngramSize) {
        Map<String, Document> categoryDocumentMap = new HashMap<>();
        for (ReviewCategory reviewCategory : reviewDistribution) {
            categoryDocumentMap.put(reviewCategory.getCategory(), reviewCategory.asDocument(ngramSize));
        }
        return categoryDocumentMap;
    }
}
