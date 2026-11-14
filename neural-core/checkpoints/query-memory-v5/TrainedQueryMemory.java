package com.omnidev.workspace.domain.neural;
import java.util.List;
public final class TrainedQueryMemory{
public static SupportQueryMemory createCandidate(List<ProductSupportModel.Article>d,List<SupportQueryMemory.Exemplar>e){return new SupportQueryMemory(d,TrainedExpandedSupport.createCandidate(d),e,new float[]{0.33333334f,0.33333334f,0.33333334f},0.0,0.05,0.5,"7d944e3bdeb29189a39b4ee759312dd0d1f366fdb1d04da49eb3f4a5a6909da1","train-query-tfidf-word12-char34-v1");}
public static ProductSupportModel retainedDefault(List<ProductSupportModel.Article>d){return TrainedProductSupport.create(d);}
private TrainedQueryMemory(){}
}
