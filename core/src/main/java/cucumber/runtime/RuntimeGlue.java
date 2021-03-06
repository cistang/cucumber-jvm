package cucumber.runtime;

import cucumber.runtime.autocomplete.MetaStepdef;
import cucumber.runtime.autocomplete.StepdefGenerator;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.io.URLOutputStream;
import cucumber.runtime.io.UTF8OutputStreamWriter;
import cucumber.runtime.model.CucumberFeature;
import cucumber.runtime.xstream.LocalizedXStreams;
import gherkin.I18n;
import gherkin.deps.com.google.gson.Gson;
import gherkin.deps.com.google.gson.GsonBuilder;
import gherkin.formatter.Argument;
import gherkin.formatter.model.Step;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static cucumber.runtime.model.CucumberFeature.load;
import static java.util.Collections.emptyList;

public class RuntimeGlue implements Glue {
    private static final List<Object> NO_FILTERS = emptyList();

    private final Map<String, StepDefinition> stepDefinitionsByPattern = new TreeMap<String, StepDefinition>();
    private final List<HookDefinition> beforeHooks = new ArrayList<HookDefinition>();
    private final List<HookDefinition> afterHooks = new ArrayList<HookDefinition>();

    private final UndefinedStepsTracker tracker;
    private final LocalizedXStreams localizedXStreams;

    public RuntimeGlue(UndefinedStepsTracker tracker, LocalizedXStreams localizedXStreams) {
        this.tracker = tracker;
        this.localizedXStreams = localizedXStreams;
    }

    @Override
    public void addStepDefinition(StepDefinition stepDefinition) {
        StepDefinition previous = stepDefinitionsByPattern.get(stepDefinition.getPattern());
        if (previous != null) {
            throw new DuplicateStepDefinitionException(previous, stepDefinition);
        }
        stepDefinitionsByPattern.put(stepDefinition.getPattern(), stepDefinition);
    }

    @Override
    public void addBeforeHook(HookDefinition hookDefinition) {
        beforeHooks.add(hookDefinition);
        Collections.sort(beforeHooks, new HookComparator(true));
    }

    @Override
    public void addAfterHook(HookDefinition hookDefinition) {
        afterHooks.add(hookDefinition);
        Collections.sort(afterHooks, new HookComparator(false));
    }

    @Override
    public List<HookDefinition> getBeforeHooks() {
        return beforeHooks;
    }

    @Override
    public List<HookDefinition> getAfterHooks() {
        return afterHooks;
    }

    @Override
    public StepDefinitionMatch stepDefinitionMatch(String uri, Step step, I18n i18n) {
        List<StepDefinitionMatch> matches = stepDefinitionMatches(uri, step);
        try {
            if (matches.size() == 0) {
                tracker.addUndefinedStep(step, i18n);
                return null;
            }
            if (matches.size() == 1) {
                return matches.get(0);
            } else {
                throw new AmbiguousStepDefinitionsException(matches);
            }
        } finally {
            tracker.storeStepKeyword(step, i18n);
        }
    }

    private List<StepDefinitionMatch> stepDefinitionMatches(String uri, Step step) {
        List<StepDefinitionMatch> result = new ArrayList<StepDefinitionMatch>();
        for (StepDefinition stepDefinition : stepDefinitionsByPattern.values()) {
            List<Argument> arguments = stepDefinition.matchedArguments(step);
            if (arguments != null) {
                result.add(new StepDefinitionMatch(arguments, stepDefinition, uri, step, localizedXStreams));
            }
        }
        return result;
    }

    @Override
    public void writeStepdefsJson(ResourceLoader resourceLoader, List<String> featurePaths, URL dotCucumber) {
        if (dotCucumber != null) {
            List<CucumberFeature> features = load(resourceLoader, featurePaths, NO_FILTERS);
            List<MetaStepdef> metaStepdefs = new StepdefGenerator().generate(stepDefinitionsByPattern.values(), features);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(metaStepdefs);

            try {
                URL stepdefsUrl = new URL(dotCucumber, "stepdefs.json");
                Writer stepdefsJson = new UTF8OutputStreamWriter(new URLOutputStream(stepdefsUrl));
                stepdefsJson.append(json);
                stepdefsJson.close();
            } catch (IOException e) {
                throw new CucumberException("Failed to write stepdefs.json", e);
            }
        }
    }
}
