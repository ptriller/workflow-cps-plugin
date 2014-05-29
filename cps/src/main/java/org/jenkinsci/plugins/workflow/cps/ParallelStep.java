package org.jenkinsci.plugins.workflow.cps;

import com.cloudbees.groovy.cps.Outcome;
import com.google.common.util.concurrent.FutureCallback;
import groovy.lang.Closure;
import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.cps.persistence.PersistIn;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static org.jenkinsci.plugins.workflow.cps.persistence.PersistenceContext.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class ParallelStep extends Step {
    /**
     * All the sub-workflows as {@link Closure}s, keyed by their names.
     */
    private final Map<String,Closure> closures;

    public ParallelStep(Map<String,Closure> closures) {
        this.closures = closures;
    }

    @Override
    public boolean start(StepContext context) throws Exception {
        CpsStepContext cps = (CpsStepContext) context;
        CpsThread t = CpsThread.current();

        ResultHandler r = new ResultHandler(context);

        for (Entry<String,Closure> e : closures.entrySet()) {
            cps.invokeBodyLater(
                t.group.export(e.getValue()),
                r.callbackFor(e.getKey())
            );
        }

        return false;
    }

    @PersistIn(PROGRAM)
    static class ResultHandler implements Serializable {
        private final StepContext context;
        /**
         * Collect the results of sub-workflows as they complete.
         * The key set is fully populated from the beginning.
         */
        private final Map<String,Outcome> outcomes = new HashMap<String, Outcome>();

        ResultHandler(StepContext context) {
            this.context = context;
        }

        Callback callbackFor(String name) {
            outcomes.put(name, null);
            return new Callback(name);
        }

        class Callback implements FutureCallback, Serializable {
            private final String name;

            Callback(String name) {
                this.name = name;
            }

            @Override
            public void onSuccess(Object result) {
                outcomes.put(name,new Outcome(result,null));
                checkAllDone();
            }

            @Override
            public void onFailure(Throwable t) {
                outcomes.put(name,new Outcome(null,t));
                checkAllDone();
            }

            private void checkAllDone() {
                for (Outcome o : outcomes.values()) {
                    if (o==null)
                        return; // some of the results are not yet ready
                }

                // all done
                context.onSuccess(outcomes);
            }

            private static final long serialVersionUID = 1L;
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "parallel";
        }

        @Override
        public Step newInstance(Map<String,Object> arguments) {
            for (Entry<String,Object> e : arguments.entrySet()) {
                if (!(e.getValue() instanceof Closure))
                    throw new IllegalArgumentException("Expected a closure but found "+e.getKey()+"="+e.getValue());
            }
            return new ParallelStep((Map)arguments);
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return Collections.<Class<?>>singleton(TaskListener.class);
        }

        /**
         * Block arguments would have to be wrapped into a list and passed as such.
         * It doesn't make sense to do the following as it is single-thread:
         *
         * <pre>
         * parallel {
         *      foo();
         * }
         * </pre>
         */
        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }

        @Override
        public String getDisplayName() {
            return "Execute sub-workflows in parallel";
        }
    }
}