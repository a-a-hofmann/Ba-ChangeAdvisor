package ch.uzh.ifi.seal.changeadvisor.service;


import ch.uzh.ifi.seal.changeadvisor.batch.job.feedbackprocessing.TransformedFeedback;
import ch.uzh.ifi.seal.changeadvisor.batch.job.feedbackprocessing.TransformedFeedbackRepository;
import ch.uzh.ifi.seal.changeadvisor.batch.job.reviews.Review;
import ch.uzh.ifi.seal.changeadvisor.batch.job.tfidf.Label;
import ch.uzh.ifi.seal.changeadvisor.batch.job.tfidf.LabelRepository;
import ch.uzh.ifi.seal.changeadvisor.tfidf.AbstractNGram;
import ch.uzh.ifi.seal.changeadvisor.tfidf.Corpus;
import ch.uzh.ifi.seal.changeadvisor.tfidf.Document;
import ch.uzh.ifi.seal.changeadvisor.tfidf.TfidfService;
import ch.uzh.ifi.seal.changeadvisor.web.dto.*;
import com.google.common.collect.ImmutableSet;
import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReviewAggregationService {

    private static final Logger logger = Logger.getLogger(ReviewAggregationService.class);

    private static final Set<String> ardocCategories = ImmutableSet
            .of("FEATURE REQUEST", "INFORMATION SEEKING", "INFORMATION GIVING", "PROBLEM DISCOVERY", "OTHER");

    private final MongoTemplate mongoOperations;

    private final TfidfService tfidfService;

    private final TransformedFeedbackRepository transformedFeedbackRepository;

    private final LabelRepository labelRepository;

    @Autowired
    public ReviewAggregationService(MongoTemplate mongoOperations, TfidfService tfidfService,
                                    TransformedFeedbackRepository transformedFeedbackRepository,
                                    LabelRepository labelRepository) {
        this.mongoOperations = mongoOperations;
        this.tfidfService = tfidfService;
        this.transformedFeedbackRepository = transformedFeedbackRepository;
        this.labelRepository = labelRepository;
    }

    /**
     * Generates a category distribution report.
     * Groups reviews by ardoc category.
     *
     * @param appName application for which we want to generate a report.
     * @return distribution report.
     * @see ReviewDistributionReport
     * @see ch.uzh.ifi.seal.changeadvisor.batch.job.ardoc.ArdocResult#category
     */
    public ReviewDistributionReport groupByCategories(final String appName) {
        List<ReviewCategory> categories;
        try {
            TypedAggregation<TransformedFeedback> categoryAggregation = Aggregation.newAggregation(TransformedFeedback.class,
                    Aggregation.match(Criteria.where("ardocResult.appName").is(appName)),
                    Aggregation.group("ardocResult.category").first("ardocResult.category").as("category") // set group by field and save it as 'category' in resulting object.
                            .push("$$ROOT").as("reviews") // push entire document to field 'reviews' in ReviewCategory.
            ).withOptions(Aggregation.newAggregationOptions().cursorBatchSize(1).allowDiskUse(true).build());

            CloseableIterator<ReviewCategory> groupResults =
                    mongoOperations.aggregateStream(categoryAggregation, TransformedFeedback.class, ReviewCategory.class); // In case the resulting doc is > 16MB it will throw error.

            categories = new ArrayList<>();
            while (groupResults.hasNext()) {
                ReviewCategory category = groupResults.next();
                categories.add(category);
            }

            return new ReviewDistributionReport(categories);
        } catch (UncategorizedMongoDbException e) {
            logger.error("Reached BSON memory limits. Running queries one by one...", e);

            categories = new ArrayList<>();
            for (String category : ardocCategories) {
                List<TransformedFeedback> reviews =
                        transformedFeedbackRepository.findAllByArdocResultCategoryAndArdocResultAppName(category, appName);

                ReviewCategory reviewCategory = new ReviewCategory(reviews, category);
                categories.add(reviewCategory);
            }

        }

        return new ReviewDistributionReport(categories);
    }

    /**
     * Retrieves the reviews based on the top N labels.
     * Fetches all reviews which contain these top labels.
     *
     * @param dto object representing the parameters we use to compute the top N labels
     *            (e.g. how many labels and for which app)
     * @return reviews for the top N labels.
     */
    public List<LabelWithReviews> reviewsByTopNLabelsByCategory(ReviewsByTopLabelsDto dto) {
        final int limit = dto.getLimit();
        List<Label> labels = labelRepository.findByAppNameAndCategoryAndNgramSizeOrderByScoreDesc(dto.getApp(), dto.getCategory(), dto.getNgrams());
        labels = getLabelsUpTo(labels, limit);

        logger.info(String.format("Fetching reviews for top %d labels: %s", dto.getLimit(), labels));
        List<LabelWithReviews> labelWithReviews = new ArrayList<>(labels.size());
        for (Label label : labels) {
            List<TransformedFeedback> feedback = transformedFeedbackRepository.findDistinctByArdocResultAppNameAndArdocResultCategoryAndTransformedSentenceContainingIgnoreCase(dto.getApp(), dto.getCategory(), label.getLabel());
            // Two ardoc results could be mapped to the same review, so in this step we remove duplicate reviews.
            List<Review> reviews = feedback.stream().map(TransformedFeedback::getReview).distinct().collect(Collectors.toList());
            labelWithReviews.add(new LabelWithReviews(label, reviews));
        }

        return labelWithReviews;
    }

    private List<Label> getLabelsUpTo(List<Label> labels, final int limit) {
        final int labelCount = labels.size();
        List<Label> labelsUpTo;
        if (limit <= labelCount) {
            labelsUpTo = labels.subList(0, limit);
        } else {
            labelsUpTo = labels.subList(0, Math.min(10, labelCount));
        }
        return labelsUpTo;
    }

    /**
     * Retrieves the reviews based on the top N labels.
     * Fetches all reviews which contain these top labels.
     *
     * @param dto object representing the parameters we use to compute the top N labels
     *            (e.g. how many labels and for which app)
     * @return reviews for the top N labels.
     */
    public List<LabelWithReviews> reviewsByTopNLabels(ReviewsByTopLabelsDto dto) {
        final int limit = dto.getLimit();
        List<Label> labels = labelRepository.findByAppNameAndNgramSizeOrderByScoreDesc(dto.getApp(), dto.getNgrams());
        labels = getLabelsUpTo(labels, limit);

        logger.info(String.format("Fetching reviews for top %d labels: %s", dto.getLimit(), labels));
        List<LabelWithReviews> labelWithReviews = new ArrayList<>(labels.size());
        for (Label label : labels) {
            List<TransformedFeedback> feedback = transformedFeedbackRepository.findByArdocResultAppNameAndTransformedSentenceContainingIgnoreCase(dto.getApp(), label.getLabel());
            // Two ardoc results could be mapped to the same review, so in this step we remove duplicate reviews.
            List<ReviewWithCategory> reviews = feedback.stream().map(f -> new ReviewWithCategory(f.getReview(), f.getCategory())).distinct().collect(Collectors.toList());
            java.util.Collections.sort(reviews);
            labelWithReviews.add(new LabelWithReviews(label, reviews));
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
    public List<Label> topNLabels(ReviewsByTopLabelsDto dto) {
        // READER
        ReviewDistributionReport reviewsByCategory = groupByCategories(dto.getApp());
        final String category = dto.getCategory();
        Assert.isTrue(reviewsByCategory.hasCategory(category), String.format("Unknown category %s", category));

        // PROCESSOR
        List<Label> tokensWithScore = getNgramTokensWithScore(reviewsByCategory, dto);

        Collections.sort(tokensWithScore, Collections.reverseOrder());

        // WRITER
        final int limit = dto.getLimit();
        if (limit >= tokensWithScore.size()) {
            return tokensWithScore;
        }
        return tokensWithScore.subList(0, limit);
    }

    private List<Label> getNgramTokensWithScore(ReviewDistributionReport reviewsByCategory, ReviewsByTopLabelsDto dto) {
        Map<String, Document> categoryDocumentMap = mapReviewsToDocuments(reviewsByCategory, dto.getNgrams());

        Corpus corpus = new Corpus(categoryDocumentMap.values());
        Document document = categoryDocumentMap.get(dto.getCategory());
        List<AbstractNGram> uniqueTokens = document.uniqueTokens();

        return tfidfService.computeTfidfScoreForTokens(dto.getApp(), dto.getCategory(), uniqueTokens, document, corpus);
    }

    private Map<String, Document> mapReviewsToDocuments(ReviewDistributionReport reviewDistribution, final int ngramSize) {
        Map<String, Document> categoryDocumentMap = new HashMap<>();
        for (ReviewCategory reviewCategory : reviewDistribution) {
            categoryDocumentMap.put(reviewCategory.getCategory(), reviewCategory.asDocument(ngramSize));
        }
        return categoryDocumentMap;
    }
}
