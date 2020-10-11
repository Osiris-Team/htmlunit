package com.gargoylesoftware.htmlunit.javascript.enhanced;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.javascript.HtmlUnitWrapFactory;
import net.sourceforge.htmlunit.corejs.javascript.WrapFactory;
import net.sourceforge.htmlunit.corejs.javascript.debug.Debugger;

public class EnhancedHtmlUnitContextFactory {

    private ScriptRunner scriptRunner_;

    private static final int INSTRUCTION_COUNT_THRESHOLD = 10_000;

    private final WebClient webClient_;
    private final BrowserVersion browserVersion_;
    private long timeout_;
    private Debugger debugger_;
    private final WrapFactory wrapFactory_ = new HtmlUnitWrapFactory();
    private boolean deminifyFunctionCode_;

    /**
     * Creates a new instance of EnhancedHtmlUnitContextFactory.
     * Uses Graaljs by default.
     * @param webClient the web client using this factory.
     */
    public EnhancedHtmlUnitContextFactory(final WebClient webClient) {
        this(webClient, 0);
    }

    /**
     * Creates a new instance of EnhancedHtmlUnitContextFactory.
     * @param webClient the web client using this factory.
     * @param engine value from 0 - 2. Selects the engine.
     * <ul>
     *   <li>0 = GRAALJS (default)</li>
     *   <li>1 = JRE</li>
     *   <li>2 = V8</li>
     * </ul>
     */
    public EnhancedHtmlUnitContextFactory(final WebClient webClient, int engine) {
        webClient_ = webClient;
        browserVersion_ = webClient.getBrowserVersion();
        setEngine(engine);
    }

    /**
     * Method for engine initialisation.
     * Select the engine:
     * <ul>
     *   <li>0 = GRAALJS (default)</li>
     *   <li>1 = JRE</li>
     *   <li>2 = V8</li>
     * </ul>
     * @param engine
     */
    public void setEngine(int engine){
        if (engine==0){
            ScriptRunnerFactory.setRunnerType(ScriptRunnerFactory.RunnerType.GRAALJS);
            scriptRunner_ = ScriptRunnerFactory.createRunner();
        }
        else if (engine==1){
            ScriptRunnerFactory.setRunnerType(ScriptRunnerFactory.RunnerType.JRE);
            scriptRunner_ = ScriptRunnerFactory.createRunner();
        }
        else {
            ScriptRunnerFactory.setRunnerType(ScriptRunnerFactory.RunnerType.V8);
            scriptRunner_ = ScriptRunnerFactory.createRunner();
        }
    }

}
