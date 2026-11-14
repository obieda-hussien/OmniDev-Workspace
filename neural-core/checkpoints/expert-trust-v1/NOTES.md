# Initial counterfactual trust result

Frozen base, four fitted selectors; selected tanh8/seed42 (186 parameters). This snapshot is explicitly ineligible for production.

On 1,462 previously observed held-out rows (399 supported, 1,063 refusals), accepted-correct=247, unsupported refused=678, earlier-correct losses=2. New negative-only public rows:27/40 Arabic and29/40 English refusals. These are no longer unseen after this evaluation.

Real training controls found592 counterfactual rows:553 use targets,39 keep targets. Strata:ar:keep20,ar:use281,en:keep19,en:use267,mixed:use5. **No mixed-language keep counterfactual was available.** Only four raw-expert rejections of a correct teacher were present across all training rows.

The next separately frozen rehearsal protocol addresses this training coverage gap. This snapshot's weights, report and losses remain archived; it cannot be described as a fresh positive/generalization success. Existing application, permissions, Room and finish authority remain unchanged.
