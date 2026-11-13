package com.omnidev.workspace.domain.neural;

import java.util.*;
import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;

/** Fit independent prior-domain trust thresholds against actual outcome utility and individual retention. */
public final class ExpertPolicyTrainer {
    static final class Observation {
        final Row row; final TinyDecisionModel.Decision before, expert;
        Observation(Row row,TinyDecisionModel.Decision before,TinyDecisionModel.Decision expert){this.row=row;this.before=before;this.expert=expert;}
    }
    static final class Policy {
        final double[] reject, change; final double utility;
        Policy(double[] reject,double[] change,double utility){this.reject=reject.clone();this.change=change.clone();this.utility=utility;}
    }
    static Policy fit(List<Observation> rows) {
        if(rows==null||rows.isEmpty()||rows.size()>10_000)throw new IllegalArgumentException("Invalid bounded policy observations");
        Map<String,Integer> totals=new TreeMap<>();
        for(Observation o:rows){if(!o.row.split.equals("development"))throw new IllegalArgumentException("Policy fitting requires development only");totals.merge(o.row.language+":"+o.row.label,1,Integer::sum);}
        double[] reject=new double[5],change=new double[5];Arrays.fill(reject,1.1);Arrays.fill(change,1.1);
        for(int domain=0;domain<5;domain++) {
            double best=-1;
            for(double r:new double[]{1.1,.99,.95,.9,.8,.7,.6,.5})for(double c:new double[]{1.1,.99,.95,.9,.8,.7,.6}) {
                double score=0;boolean feasible=true;
                for(Observation o:rows)if(o.before.status==TinyDecisionModel.Status.CHOICE&&UtilityExpertDecisionModel.domain(o.before.choice)==domain){
                    TinyDecisionModel.Decision after=UtilityExpertDecisionModel.choose(o.before,o.expert,r,c);
                    boolean correct=correct(o.row,after);
                    if(o.row.label==0&&Objects.equals(o.before.choice,o.row.expected)&&!correct)feasible=false;
                    if(correct)score+=1.0/totals.get(o.row.language+":"+o.row.label);
                }
                if(feasible&&score>best+1e-12){best=score;reject[domain]=r;change[domain]=c;}
            }
        }
        double sum=0;
        for(Observation o:rows){TinyDecisionModel.Decision after=o.before.status==TinyDecisionModel.Status.ABSTAIN?o.before:UtilityExpertDecisionModel.choose(o.before,o.expert,reject[UtilityExpertDecisionModel.domain(o.before.choice)],change[UtilityExpertDecisionModel.domain(o.before.choice)]);
            if(o.row.label==0&&Objects.equals(o.before.choice,o.row.expected)&&!correct(o.row,after))throw new AssertionError("Policy lost an earlier correct development choice");
            if(correct(o.row,after))sum+=1.0/totals.get(o.row.language+":"+o.row.label);
        }
        return new Policy(reject,change,sum/totals.size());
    }
    static boolean correct(Row row,TinyDecisionModel.Decision decision){return row.label==1?decision.status==TinyDecisionModel.Status.ABSTAIN:Objects.equals(row.expected,decision.choice);}
    private ExpertPolicyTrainer(){ }
}
