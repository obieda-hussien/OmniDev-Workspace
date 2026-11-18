package com.omnidev.workspace.domain.neural;
import java.util.List;
public final class TrainedHybridSupport{
public static HybridSupportRetriever createCandidate(List<ProductSupportModel.Article> a){return new HybridSupportRetriever(a,HybridSupportRetriever.Mode.LEARNED,new float[]{0.32666817f,0.07891732f,0.41298637f,0.18142816f,0.0f,0.0f,0.0f,0.0f},0.0,0.0,0.5,"354285991e1343ab6aa39dbb817f6858df24560b9224fa4d9cf9e331f5db62ad","bilingual-fields-light-ar-8-v1");}
public static ProductSupportModel retainedDefault(List<ProductSupportModel.Article>a){return TrainedProductSupport.create(a);}
private TrainedHybridSupport(){}
}
