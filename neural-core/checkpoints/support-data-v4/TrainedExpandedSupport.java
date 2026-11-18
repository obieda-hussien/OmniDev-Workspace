package com.omnidev.workspace.domain.neural;
import java.util.List;
public final class TrainedExpandedSupport{
public static HybridSupportRetriever createCandidate(List<ProductSupportModel.Article> a){return new HybridSupportRetriever(a,HybridSupportRetriever.Mode.LEARNED,new float[]{0.22677237f,0.089743815f,0.25192976f,0.12160595f,0.032644227f,0.12721106f,0.026137162f,0.12395565f},0.0,0.0,0.5,"354285991e1343ab6aa39dbb817f6858df24560b9224fa4d9cf9e331f5db62ad","bilingual-fields-light-ar-8-v1");}
public static ProductSupportModel retainedDefault(List<ProductSupportModel.Article>a){return TrainedProductSupport.create(a);}
private TrainedExpandedSupport(){}
}
