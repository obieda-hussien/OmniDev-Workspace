package com.omnidev.workspace.domain.neural;
import java.util.List;
public final class TrainedRankedSupport {
 public static ProductSupportModel createRaw(List<ProductSupportModel.Article> corpus){return new ProductSupportModel(corpus,network(),0.875,0.0,"354285991e1343ab6aa39dbb817f6858df24560b9224fa4d9cf9e331f5db62ad");}
 public static ProductSupportModel createCandidateGuarded(List<ProductSupportModel.Article> corpus){return new ProductSupportModel(corpus,network(),0.875,0.0,"354285991e1343ab6aa39dbb817f6858df24560b9224fa4d9cf9e331f5db62ad",new ProductSupportModel.Guard(0.925,0.0));}
 public static ProductSupportModel baseline(List<ProductSupportModel.Article> corpus){return new ProductSupportModel(corpus,null,0.925,0.0,"354285991e1343ab6aa39dbb817f6858df24560b9224fa4d9cf9e331f5db62ad");}
 public static TinyNeuralRuntime network(){return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(12,1,new float[]{4.7882056f,0.1610273f,0.18455344f,-0.09128117f,0.20095374f,0.16171712f,-0.22913046f,-0.22296853f,0.03674782f,0.0f,0.15967886f,-0.23149312f},new float[]{-0.23421815f},TinyNeuralRuntime.Activation.LINEAR));}
 private TrainedRankedSupport(){}
public static ProductSupportModel create(java.util.List<ProductSupportModel.Article> corpus){return TrainedProductSupport.create(corpus);}
}
