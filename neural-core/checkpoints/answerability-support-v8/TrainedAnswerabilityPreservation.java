package com.omnidev.workspace.domain.neural;
import java.util.List;
public final class TrainedAnswerabilityPreservation {
public static SupportAnswerabilityModel createCandidate(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return TrainedAnswerabilitySupport.createCandidate(d,e).withThreshold(0.425);}
public static ProductSupportModel retainedDefault(List<ProductSupportModel.Article>d){return TrainedProductSupport.create(d);}
private TrainedAnswerabilityPreservation(){}
}
