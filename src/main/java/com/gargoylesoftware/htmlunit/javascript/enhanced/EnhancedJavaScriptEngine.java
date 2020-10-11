package com.gargoylesoftware.htmlunit.javascript.enhanced;

import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.javascript.*;
import com.gargoylesoftware.htmlunit.javascript.background.BackgroundJavaScriptFactory;
import com.gargoylesoftware.htmlunit.javascript.background.JavaScriptExecutor;
import com.gargoylesoftware.htmlunit.javascript.configuration.ClassConfiguration;
import com.gargoylesoftware.htmlunit.javascript.configuration.JavaScriptConfiguration;
import com.gargoylesoftware.htmlunit.javascript.host.*;
import com.gargoylesoftware.htmlunit.javascript.host.intl.Intl;
import com.gargoylesoftware.htmlunit.javascript.host.xml.FormData;
//import net.sourceforge.htmlunit.corejs.javascript.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.*;

import static com.gargoylesoftware.htmlunit.BrowserVersionFeatures.*;

public class EnhancedJavaScriptEngine implements AbstractJavaScriptEngine<Script> {

    private static final Log LOG = LogFactory.getLog(EnhancedJavaScriptEngine.class);

    private WebClient webClient_;
    private final EnhancedHtmlUnitContextFactory enhancedContextFactory_;
    private final JavaScriptConfiguration jsConfig_;

    private transient ThreadLocal<Boolean> javaScriptRunning_;
    private transient ThreadLocal<List<PostponedAction>> postponedActions_;
    private transient boolean holdPostponedActions_;

    /** The JavaScriptExecutor corresponding to all windows of this Web client */
    private transient JavaScriptExecutor javaScriptExecutor_;

    /**
     * Key used to place the scope in which the execution of some JavaScript code
     * started as thread local attribute in current context.
     * <p>This is needed to resolve some relative locations relatively to the page
     * in which the script is executed and not to the page which location is changed.
     */
    public static final String KEY_STARTING_SCOPE = "startingScope";

    /**
     * Key used to place the {@link HtmlPage} for which the JavaScript code is executed
     * as thread local attribute in current context.
     */
    public static final String KEY_STARTING_PAGE = "startingPage";

    /**
     * Creates an instance for the specified {@link WebClient}.
     *
     * @param webClient the client that will own this engine
     */
    public EnhancedJavaScriptEngine(final WebClient webClient) {
        if (webClient == null) {
            throw new IllegalArgumentException("EnhancedJavaScriptEngine ctor requires a webClient");
        }

        webClient_ = webClient;
        enhancedContextFactory_ = new EnhancedHtmlUnitContextFactory(webClient);
        initTransientFields();

        jsConfig_ = JavaScriptConfiguration.getInstance(webClient.getBrowserVersion());
        RhinoException.useMozillaStackStyle(true);
    }

    private void initTransientFields() {
        javaScriptRunning_ = new ThreadLocal<>();
        postponedActions_ = new ThreadLocal<>();
        holdPostponedActions_ = false;
    }

    /**
     * Returns the web client that this engine is associated with.
     * @return the web client
     */
    private WebClient getWebClient() {
        return webClient_;
    }

    /**
     * Gets the associated configuration.
     * @return the configuration
     */
    @Override
    public JavaScriptConfiguration getJavaScriptConfiguration() {
        return jsConfig_;
    }

    /**
     * Adds an action that should be executed first when the script currently being executed has finished.
     * @param action the action
     */
    @Override
    public void addPostponedAction(final PostponedAction action) {
        List<PostponedAction> actions = postponedActions_.get();
        if (actions == null) {
            actions = new ArrayList<>();
            postponedActions_.set(actions);
        }
        actions.add(action);
    }

    /**
     * <span style="color:red">INTERNAL API - SUBJECT TO CHANGE AT ANY TIME - USE AT YOUR OWN RISK.</span><br>
     * Process postponed actions, if any.
     */
    @Override
    public void processPostponedActions() {
        doProcessPostponedActions();
    }

    void doProcessPostponedActions() {
        holdPostponedActions_ = false;

        final WebClient webClient = getWebClient();
        // shutdown was already called
        if (webClient == null) {
            postponedActions_.set(null);
            return;
        }

        try {
            webClient.loadDownloadedResponses();
        }
        catch (final RuntimeException e) {
            throw e;
        }
        catch (final Exception e) {
            throw new RuntimeException(e);
        }

        final List<PostponedAction> actions = postponedActions_.get();
        if (actions != null) {
            postponedActions_.set(null);
            try {
                for (final PostponedAction action : actions) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Processing PostponedAction " + action);
                    }

                    // verify that the page that registered this PostponedAction is still alive
                    if (action.isStillAlive()) {
                        action.execute();
                    }
                }
            }
            catch (final Exception e) {
                EnhancedContext.throwAsScriptRuntimeEx(e);
            }
        }
    }

    /**
     * Register WebWindow with the JavaScriptExecutor.
     * @param webWindow the WebWindow to be registered.
     */
    @Override
    public synchronized void registerWindowAndMaybeStartEventLoop(final WebWindow webWindow) {
        final WebClient webClient = getWebClient();
        if (webClient != null) {
            if (javaScriptExecutor_ == null) {
                javaScriptExecutor_ = BackgroundJavaScriptFactory.theFactory().createJavaScriptExecutor(webClient);
            }
            javaScriptExecutor_.addWindow(webWindow);
        }
    }

    /**
     * Performs initialization for the given webWindow.
     * @param webWindow the web window to initialize for
     */
    @Override
    public void initialize(final WebWindow webWindow) {
        WebAssert.notNull("webWindow", webWindow);

        getEnhancedContextFactory().call(cx -> {
            try {
                init(webWindow, cx);
            }
            catch (final Exception e) {
                LOG.error("Exception while initializing JavaScript for the page", e);
                throw new ScriptException(null, e); // BUG: null is not useful.
            }
            return null;
        });
    }

    /**
     * Initializes all the JS stuff for the window.
     * @param webWindow the web window
     * @param enhancedContext the current context
     * @throws Exception if something goes wrong
     */
    private void init(final WebWindow webWindow, final EnhancedContext enhancedContext) throws Exception {
        final WebClient webClient = webWindow.getWebClient();
        final BrowserVersion browserVersion = webClient.getBrowserVersion();

        final Window window = new Window();
        ((SimpleScriptable) window).setClassName("Window");
        enhancedContext.initSafeStandardObjects(window);

        final ClassConfiguration windowConfig = jsConfig_.getClassConfiguration("Window");
        if (windowConfig.getJsConstructor() != null) {
            final FunctionObject functionObject = new RecursiveFunctionObject("Window",
                    windowConfig.getJsConstructor(), window);
            ScriptableObject.defineProperty(window, "constructor", functionObject,
                    ScriptableObject.DONTENUM  | ScriptableObject.PERMANENT | ScriptableObject.READONLY);
        }
        else {
            defineConstructor(window, window, new Window());
        }

        // remove some objects, that Rhino defines in top scope but that we don't want
        deleteProperties(window, "Continuation");
        if (!browserVersion.hasFeature(JS_XML)) {
            deleteProperties(window, "XML", "XMLList", "Namespace", "QName");
        }

        deleteProperties(window, "Iterator", "StopIteration");

        if (!browserVersion.hasFeature(JS_SYMBOL)) {
            deleteProperties(window, "Symbol");
        }

        final ScriptableObject errorObject = (ScriptableObject) ScriptableObject.getProperty(window, "Error");
        if (browserVersion.hasFeature(JS_ERROR_STACK_TRACE_LIMIT)) {
            errorObject.defineProperty("stackTraceLimit", 10, ScriptableObject.EMPTY);
        }
        else {
            ScriptableObject.deleteProperty(errorObject, "stackTraceLimit");
        }
        if (!browserVersion.hasFeature(JS_ERROR_CAPTURE_STACK_TRACE)) {
            ScriptableObject.deleteProperty(errorObject, "captureStackTrace");
        }

        if (browserVersion.hasFeature(JS_URL_SEARCH_PARMS_ITERATOR_SIMPLE_NAME)) {
            URLSearchParams.NativeParamsIterator.init(window, "Iterator");
        }
        else {
            URLSearchParams.NativeParamsIterator.init(window, "URLSearchParams Iterator");
        }
        if (browserVersion.hasFeature(JS_FORM_DATA_ITERATOR_SIMPLE_NAME)) {
            FormData.FormDataIterator.init(window, "Iterator");
        }
        else {
            FormData.FormDataIterator.init(window, "FormData Iterator");
        }

        final Intl intl = new Intl();
        intl.setParentScope(window);
        window.defineProperty(intl.getClassName(), intl, ScriptableObject.DONTENUM);
        intl.defineProperties(browserVersion);

        if (browserVersion.hasFeature(JS_REFLECT)) {
            final Reflect reflect = new Reflect();
            reflect.setParentScope(window);
            window.defineProperty(reflect.getClassName(), reflect, ScriptableObject.DONTENUM);
            reflect.defineProperties();
        }

        final Map<Class<? extends Scriptable>, Scriptable> prototypes = new HashMap<>();
        final Map<String, Scriptable> prototypesPerJSName = new HashMap<>();

        final String windowClassName = Window.class.getName();
        for (final ClassConfiguration config : jsConfig_.getAll()) {
            final boolean isWindow = windowClassName.equals(config.getHostClass().getName());
            if (isWindow) {
                configureConstantsPropertiesAndFunctions(config, window);

                final HtmlUnitScriptable prototype = configureClass(config, window, browserVersion);
                prototypesPerJSName.put(config.getClassName(), prototype);
            }
            else {
                final HtmlUnitScriptable prototype = configureClass(config, window, browserVersion);
                if (config.isJsObject()) {
                    // Place object with prototype property in Window scope
                    final HtmlUnitScriptable obj = config.getHostClass().newInstance();
                    prototype.defineProperty("__proto__", prototype, ScriptableObject.DONTENUM);
                    obj.defineProperty("prototype", prototype, ScriptableObject.DONTENUM); // but not setPrototype!
                    obj.setParentScope(window);
                    obj.setClassName(config.getClassName());
                    ScriptableObject.defineProperty(window, obj.getClassName(), obj, ScriptableObject.DONTENUM);
                    // this obj won't have prototype, constants need to be configured on it again
                    configureConstants(config, obj);
                }
                prototypes.put(config.getHostClass(), prototype);
                prototypesPerJSName.put(config.getClassName(), prototype);
            }
        }

        for (final ClassConfiguration config : jsConfig_.getAll()) {
            final Executable jsConstructor = config.getJsConstructor();
            final String jsClassName = config.getClassName();
            Scriptable prototype = prototypesPerJSName.get(jsClassName);
            final String hostClassSimpleName = config.getHostClassSimpleName();

            if ("Image".equals(hostClassSimpleName)
                    && browserVersion.hasFeature(JS_IMAGE_PROTOTYPE_SAME_AS_HTML_IMAGE)) {
                prototype = prototypesPerJSName.get("HTMLImageElement");
            }
            if ("Option".equals(hostClassSimpleName)) {
                prototype = prototypesPerJSName.get("HTMLOptionElement");
            }

            switch (hostClassSimpleName) {
                case "WebKitMutationObserver":
                    prototype = prototypesPerJSName.get("MutationObserver");
                    break;

                case "webkitURL":
                    prototype = prototypesPerJSName.get("URL");
                    break;

                default:
            }
            if (prototype != null && config.isJsObject()) {
                if (jsConstructor == null) {
                    final ScriptableObject constructor;
                    if ("Window".equals(jsClassName)) {
                        constructor = (ScriptableObject) ScriptableObject.getProperty(window, "constructor");
                    }
                    else {
                        constructor = config.getHostClass().newInstance();
                        ((SimpleScriptable) constructor).setClassName(config.getClassName());
                    }
                    defineConstructor(window, prototype, constructor);
                    configureConstantsStaticPropertiesAndStaticFunctions(config, constructor);
                }
                else {
                    final BaseFunction function;
                    if ("Window".equals(jsClassName)) {
                        function = (BaseFunction) ScriptableObject.getProperty(window, "constructor");
                    }
                    else {
                        function = new RecursiveFunctionObject(jsClassName, jsConstructor, window);
                    }

                    if ("WebKitMutationObserver".equals(hostClassSimpleName)
                            || "webkitURL".equals(hostClassSimpleName)
                            || "Image".equals(hostClassSimpleName)
                            || "Option".equals(hostClassSimpleName)) {
                        final Object prototypeProperty = ScriptableObject.getProperty(window, prototype.getClassName());

                        if (function instanceof FunctionObject) {
                            try {
                                ((FunctionObject) function).addAsConstructor(window, prototype);
                            }
                            catch (final Exception e) {
                                // TODO see issue #1897
                                if (LOG.isWarnEnabled()) {
                                    final String newline = System.lineSeparator();
                                    LOG.warn("Error during EnhancedJavaScriptEngine.init(WebWindow, EnhancedContext)" + newline
                                            + e.getMessage() + newline
                                            + "prototype: " + prototype.getClassName());
                                }
                            }
                        }

                        ScriptableObject.defineProperty(window, hostClassSimpleName, function,
                                ScriptableObject.DONTENUM);

                        // the prototype class name is set as a side effect of functionObject.addAsConstructor
                        // so we restore its value
                        if (!hostClassSimpleName.equals(prototype.getClassName())) {
                            if (prototypeProperty == UniqueTag.NOT_FOUND) {
                                ScriptableObject.deleteProperty(window, prototype.getClassName());
                            }
                            else {
                                ScriptableObject.defineProperty(window, prototype.getClassName(),
                                        prototypeProperty, ScriptableObject.DONTENUM);
                            }
                        }
                    }
                    else {
                        if (function instanceof FunctionObject) {
                            try {
                                ((FunctionObject) function).addAsConstructor(window, prototype);
                            }
                            catch (final Exception e) {
                                // TODO see issue #1897
                                if (LOG.isWarnEnabled()) {
                                    final String newline = System.lineSeparator();
                                    LOG.warn("Error during EnhancedJavaScriptEngine.init(WebWindow, EnhancedContext)" + newline
                                            + e.getMessage() + newline
                                            + "prototype: " + prototype.getClassName());
                                }
                            }
                        }
                    }

                    configureConstantsStaticPropertiesAndStaticFunctions(config, function);
                }
            }
        }
        window.setPrototype(prototypesPerJSName.get(Window.class.getSimpleName()));

        // once all prototypes have been build, it's possible to configure the chains
        final Scriptable objectPrototype = ScriptableObject.getObjectPrototype(window);
        for (final Map.Entry<String, Scriptable> entry : prototypesPerJSName.entrySet()) {
            final String name = entry.getKey();
            final ClassConfiguration config = jsConfig_.getClassConfiguration(name);
            final Scriptable prototype = entry.getValue();
            if (!StringUtils.isEmpty(config.getExtendedClassName())) {
                final Scriptable parentPrototype = prototypesPerJSName.get(config.getExtendedClassName());
                prototype.setPrototype(parentPrototype);
            }
            else {
                prototype.setPrototype(objectPrototype);
            }
        }

        // IE ActiveXObject simulation
        // see http://msdn.microsoft.com/en-us/library/ie/dn423948%28v=vs.85%29.aspx
        // DEV Note: this is at the moment the only usage of HiddenFunctionObject
        //           if we need more in the future, we have to enhance our JSX annotations
        if (browserVersion.hasFeature(JS_WINDOW_ACTIVEXOBJECT_HIDDEN)) {
            final Scriptable prototype = prototypesPerJSName.get("ActiveXObject");
            if (null != prototype) {
                final Method jsConstructor = ActiveXObject.class.getDeclaredMethod("jsConstructor",
                        EnhancedContext.class, Object[].class, Function.class, boolean.class);
                final FunctionObject functionObject = new HiddenFunctionObject("ActiveXObject", jsConstructor, window);
                try {
                    functionObject.addAsConstructor(window, prototype);
                }
                catch (final Exception e) {
                    // TODO see issue #1897
                    if (LOG.isWarnEnabled()) {
                        final String newline = System.lineSeparator();
                        LOG.warn("Error during EnhancedJavaScriptEngine.init(WebWindow, EnhancedContext)" + newline
                                + e.getMessage() + newline
                                + "prototype: " + prototype.getClassName());
                    }
                }
            }
        }

        // Rhino defines too much methods for us, particularly since implementation of ECMAScript5
        final ScriptableObject stringPrototype = (ScriptableObject) ScriptableObject.getClassPrototype(window, "String");
        deleteProperties(stringPrototype, "equals", "equalsIgnoreCase");

        final ScriptableObject numberPrototype = (ScriptableObject) ScriptableObject.getClassPrototype(window, "Number");
        final ScriptableObject datePrototype = (ScriptableObject) ScriptableObject.getClassPrototype(window, "Date");

        if (!browserVersion.hasFeature(STRING_INCLUDES)) {
            deleteProperties(stringPrototype, "includes");
        }
        if (!browserVersion.hasFeature(STRING_REPEAT)) {
            deleteProperties(stringPrototype, "repeat");
        }
        if (!browserVersion.hasFeature(STRING_STARTS_ENDS_WITH)) {
            deleteProperties(stringPrototype, "startsWith", "endsWith");
        }
        if (!browserVersion.hasFeature(STRING_TRIM_LEFT_RIGHT)) {
            deleteProperties(stringPrototype, "trimLeft", "trimRight");
        }

        // only FF has toSource
        if (!browserVersion.hasFeature(JS_FUNCTION_TOSOURCE)) {
            deleteProperties(window, "uneval");
            removePrototypeProperties(window, "Object", "toSource");
            removePrototypeProperties(window, "Array", "toSource");
            deleteProperties(datePrototype, "toSource");
            removePrototypeProperties(window, "Function", "toSource");
            deleteProperties(numberPrototype, "toSource");
            deleteProperties(stringPrototype, "toSource");
        }
        if (browserVersion.hasFeature(JS_WINDOW_ACTIVEXOBJECT_HIDDEN)) {
            ((IdFunctionObject) ScriptableObject.getProperty(window, "Object")).delete("assign");

            // TODO
            deleteProperties(window, "WeakSet");
        }
        deleteProperties(window, "isXMLName");

        NativeFunctionToStringFunction.installFix(window, browserVersion);

        datePrototype.defineFunctionProperties(new String[] {"toLocaleDateString", "toLocaleTimeString"},
                DateCustom.class, ScriptableObject.DONTENUM);

        if (!browserVersion.hasFeature(JS_OBJECT_GET_OWN_PROPERTY_SYMBOLS)) {
            ((ScriptableObject) ScriptableObject.getProperty(window, "Object")).delete("getOwnPropertySymbols");
        }

        if (!browserVersion.hasFeature(JS_ARRAY_FROM)) {
            deleteProperties((ScriptableObject) ScriptableObject.getProperty(window, "Array"), "from", "of");
        }

        numberPrototype.defineFunctionProperties(new String[] {"toLocaleString"},
                NumberCustom.class, ScriptableObject.DONTENUM);

        if (!webClient.getOptions().isWebSocketEnabled()) {
            deleteProperties(window, "WebSocket");
        }

        window.setPrototypes(prototypes, prototypesPerJSName);
        window.initialize(webWindow);
    }

    private static void defineConstructor(final Window window,
                                          final Scriptable prototype, final ScriptableObject constructor) {
        constructor.setParentScope(window);
        try {
            ScriptableObject.defineProperty(prototype, "constructor", constructor,
                    ScriptableObject.DONTENUM  | ScriptableObject.PERMANENT | ScriptableObject.READONLY);
        }
        catch (final Exception e) {
            // TODO see issue #1897
            if (LOG.isWarnEnabled()) {
                final String newline = System.lineSeparator();
                LOG.warn("Error during EnhancedJavaScriptEngine.init(WebWindow, EnhancedContext)" + newline
                        + e.getMessage() + newline
                        + "prototype: " + prototype.getClassName());
            }
        }

        try {
            ScriptableObject.defineProperty(constructor, "prototype", prototype,
                    ScriptableObject.DONTENUM  | ScriptableObject.PERMANENT | ScriptableObject.READONLY);
        }
        catch (final Exception e) {
            // TODO see issue #1897
            if (LOG.isWarnEnabled()) {
                final String newline = System.lineSeparator();
                LOG.warn("Error during EnhancedJavaScriptEngine.init(WebWindow, EnhancedContext)" + newline
                        + e.getMessage() + newline
                        + "prototype: " + prototype.getClassName());
            }
        }

        window.defineProperty(constructor.getClassName(), constructor, ScriptableObject.DONTENUM);
    }

    /**
     * Deletes the properties with the provided names.
     * @param scope the scope from which properties have to be removed
     * @param propertiesToDelete the list of property names
     */
    private static void deleteProperties(final Scriptable scope, final String... propertiesToDelete) {
        for (final String property : propertiesToDelete) {
            scope.delete(property);
        }
    }

    /**
     * Removes prototype properties.
     * @param scope the scope
     * @param className the class for which properties should be removed
     * @param properties the properties to remove
     */
    private static void removePrototypeProperties(final Scriptable scope, final String className,
                                                  final String... properties) {
        final ScriptableObject prototype = (ScriptableObject) ScriptableObject.getClassPrototype(scope, className);
        for (final String property : properties) {
            prototype.delete(property);
        }
    }

    /**
     * Configures the specified class for access via JavaScript.
     * @param config the configuration settings for the class to be configured
     * @param window the scope within which to configure the class
     * @param browserVersion the browser version
     * @throws InstantiationException if the new class cannot be instantiated
     * @throws IllegalAccessException if we don't have access to create the new instance
     * @return the created prototype
     */
    public static HtmlUnitScriptable configureClass(final ClassConfiguration config, final Scriptable window,
                                                    final BrowserVersion browserVersion)
            throws InstantiationException, IllegalAccessException {

        final HtmlUnitScriptable prototype = config.getHostClass().newInstance();
        prototype.setParentScope(window);
        prototype.setClassName(config.getClassName());

        configureConstantsPropertiesAndFunctions(config, prototype);

        return prototype;
    }

    /**
     * Configures constants, static properties and static functions on the object.
     * @param config the configuration for the object
     * @param scriptable the object to configure
     */
    private static void configureConstantsStaticPropertiesAndStaticFunctions(final ClassConfiguration config,
                                                                             final ScriptableObject scriptable) {
        configureConstants(config, scriptable);
        configureStaticProperties(config, scriptable);
        configureStaticFunctions(config, scriptable);
    }

    /**
     * Configures constants, properties and functions on the object.
     * @param config the configuration for the object
     * @param scriptable the object to configure
     */
    private static void configureConstantsPropertiesAndFunctions(final ClassConfiguration config,
                                                                 final ScriptableObject scriptable) {
        configureConstants(config, scriptable);
        configureProperties(config, scriptable);
        configureSymbols(config, scriptable);
        configureFunctions(config, scriptable);
    }

    private static void configureFunctions(final ClassConfiguration config, final ScriptableObject scriptable) {
        final int attributes = ScriptableObject.EMPTY;
        // the functions
        final Map<String, Method> functionMap = config.getFunctionMap();
        if (functionMap != null) {
            for (final Map.Entry<String, Method> functionInfo : functionMap.entrySet()) {
                final String functionName = functionInfo.getKey();
                final Method method = functionInfo.getValue();
                final FunctionObject functionObject = new FunctionObject(functionName, method, scriptable);
                scriptable.defineProperty(functionName, functionObject, attributes);
            }
        }
    }

    private static void configureConstants(final ClassConfiguration config, final ScriptableObject scriptable) {
        final List<ClassConfiguration.ConstantInfo> constants = config.getConstants();
        if (constants != null) {
            for (final ClassConfiguration.ConstantInfo constantInfo : constants) {
                scriptable.defineProperty(constantInfo.getName(), constantInfo.getValue(), constantInfo.getFlag());
            }
        }
    }

    private static void configureProperties(final ClassConfiguration config, final ScriptableObject scriptable) {
        final Map<String, ClassConfiguration.PropertyInfo> propertyMap = config.getPropertyMap();
        if (propertyMap != null) {
            for (final Map.Entry<String, ClassConfiguration.PropertyInfo> propertyEntry : propertyMap.entrySet()) {
                final ClassConfiguration.PropertyInfo info = propertyEntry.getValue();
                final Method readMethod = info.getReadMethod();
                final Method writeMethod = info.getWriteMethod();
                scriptable.defineProperty(propertyEntry.getKey(), null, readMethod, writeMethod, ScriptableObject.EMPTY);
            }
        }
    }

    private static void configureStaticProperties(final ClassConfiguration config, final ScriptableObject scriptable) {
        final Map<String, ClassConfiguration.PropertyInfo> staticPropertyMap = config.getStaticPropertyMap();
        if (staticPropertyMap != null) {
            for (final Map.Entry<String, ClassConfiguration.PropertyInfo> propertyEntry : staticPropertyMap.entrySet()) {
                final String propertyName = propertyEntry.getKey();
                final Method readMethod = propertyEntry.getValue().getReadMethod();
                final Method writeMethod = propertyEntry.getValue().getWriteMethod();
                final int flag = ScriptableObject.EMPTY;

                scriptable.defineProperty(propertyName, null, readMethod, writeMethod, flag);
            }
        }
    }

    private static void configureStaticFunctions(final ClassConfiguration config,
                                                 final ScriptableObject scriptable) {
        final Map<String, Method> staticFunctionMap = config.getStaticFunctionMap();
        if (staticFunctionMap != null) {
            for (final Map.Entry<String, Method> staticFunctionInfo : staticFunctionMap.entrySet()) {
                final String functionName = staticFunctionInfo.getKey();
                final Method method = staticFunctionInfo.getValue();
                final FunctionObject staticFunctionObject = new FunctionObject(functionName, method,
                        scriptable);
                scriptable.defineProperty(functionName, staticFunctionObject, ScriptableObject.EMPTY);
            }
        }
    }

    private static void configureSymbols(final ClassConfiguration config,
                                         final ScriptableObject scriptable) {
        final Map<Symbol, Method> symbolMap = config.getSymbolMap();
        if (symbolMap != null) {
            for (final Map.Entry<Symbol, Method> symbolInfo : symbolMap.entrySet()) {
                final Callable symbolFunction = new FunctionObject(
                        symbolInfo.getKey().toString(), symbolInfo.getValue(), scriptable);
                scriptable.defineProperty(symbolInfo.getKey(), symbolFunction, ScriptableObject.DONTENUM);
            }
        }
    }

    /**
     * Returns the javascript timeout.
     * @return the javascript timeout
     */
    @Override
    public long getJavaScriptTimeout() {
        return getEnhancedContextFactory().getTimeout();
    }

    /**
     * Sets the javascript timeout.
     * @param timeout the timeout
     */
    @Override
    public void setJavaScriptTimeout(final long timeout) {
        getEnhancedContextFactory().setTimeout(timeout);
    }

    /**
     * Shutdown the EnhancedJavaScriptEngine.
     */
    @Override
    public void shutdown() {
        webClient_ = null;
        if (javaScriptExecutor_ != null) {
            javaScriptExecutor_.shutdown();
            javaScriptExecutor_ = null;
        }
        if (postponedActions_ != null) {
            postponedActions_.remove();
        }
        if (javaScriptRunning_ != null) {
            javaScriptRunning_.remove();
        }
        holdPostponedActions_ = false;
        //TODO maybe set ScriptRunner to null?
    }

    /**
     * Indicates if JavaScript is running in current thread.
     * <p>This allows code to know if there own evaluation is has been triggered by some JS code.
     * @return {@code true} if JavaScript is running
     */
    @Override
    public boolean isScriptRunning() {
        return Boolean.TRUE.equals(javaScriptRunning_.get());
    }

    /**
     * <span style="color:red">INTERNAL API - SUBJECT TO CHANGE AT ANY TIME - USE AT YOUR OWN RISK.</span><br>
     * Indicates that no postponed action should be executed.
     */
    @Override
    public void holdPosponedActions() {
        holdPostponedActions_ = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Script compile(final HtmlPage page, final String sourceCode,
                          final String sourceName, final int startLine) {
        final Scriptable scope = getScope(page, null);
        return compile(page, scope, sourceCode, sourceName, startLine);
    }

    private static Scriptable getScope(final HtmlPage page, final DomNode node) {
        if (node != null) {
            return node.getScriptableObject();
        }
        return page.getEnclosingWindow().getScriptableObject();
    }

    /**
     * Compiles the specified JavaScript code in the context of a given scope.
     *
     * @param owningPage the page from which the code started
     * @param scope the scope in which to execute the javascript code
     * @param sourceCode the JavaScript code to execute
     * @param sourceName the name that will be displayed on error conditions
     * @param startLine the line at which the script source starts
     * @return the result of executing the specified code
     */
    public Script compile(final HtmlPage owningPage, final Scriptable scope, final String sourceCode,
                          final String sourceName, final int startLine) {
        WebAssert.notNull("sourceCode", sourceCode);

        if (LOG.isTraceEnabled()) {
            final String newline = System.lineSeparator();
            LOG.trace("Javascript compile " + sourceName + newline + sourceCode + newline);
        }

        final EnhancedContextAction<Object> action = new EnhancedJavaScriptEngine.HtmlUnitContextAction(scope, owningPage) {
            @Override
            public Object doRun(final EnhancedContext cx) {
                return cx.compileString(sourceCode, sourceName, startLine, null);
            }

            @Override
            protected String getSourceCode(final EnhancedContext cx) {
                return sourceCode;
            }
        };

        return (Script) getEnhancedContextFactory().callSecured(action, owningPage);
    }

    /**
     * Returns this JavaScript engine's enhanced ContextFactory {@link net.sourceforge.htmlunit.corejs.javascript.ContextFactory}.
     * @return this JavaScript engine's enhanced ContextFactory {@link net.sourceforge.htmlunit.corejs.javascript.ContextFactory}
     */
    public EnhancedHtmlUnitContextFactory getEnhancedContextFactory() {
        return enhancedContextFactory_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object execute(final HtmlPage page,
                          final String sourceCode,
                          final String sourceName,
                          final int startLine) {

        final Script script = compile(page, sourceCode, sourceName, startLine);
        if (script == null) { // happens with syntax error + throwExceptionOnScriptError = false
            return null;
        }
        return execute(page, script);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object execute(final HtmlPage page, final Script script) {
        final Scriptable scope = getScope(page, null);
        return execute(page, scope, script);
    }

    /**
     * Executes the specified JavaScript code in the given scope.
     *
     * @param page the page that started the execution
     * @param scope the scope in which to execute
     * @param script the script to execute
     * @return the result of executing the specified code
     */
    public Object execute(final HtmlPage page, final Scriptable scope, final Script script) {
        final EnhancedContextAction<Object> action = new EnhancedJavaScriptEngine.HtmlUnitContextAction(scope, page) {
            @Override
            public Object doRun(final EnhancedContext cx) {
                return script.exec(cx, scope);
            }

            @Override
            protected String getSourceCode(final EnhancedContext cx) {
                return null;
            }
        };

        return getEnhancedContextFactory().callSecured(action, page);
    }

    /**
     * Facility for ContextAction usage.
     * ContextAction should be preferred because according to Rhino doc it
     * "guarantees proper association of Context instances with the current thread and is faster".
     */
    private abstract class HtmlUnitContextAction implements EnhancedContextAction<Object> {
        private final Scriptable scope_;
        private final HtmlPage page_;

        HtmlUnitContextAction(final Scriptable scope, final HtmlPage page) {
            scope_ = scope;
            page_ = page;
        }

        @Override
        public final Object run(final EnhancedContext cx) {
            final Boolean javaScriptAlreadyRunning = javaScriptRunning_.get();
            javaScriptRunning_.set(Boolean.TRUE);

            try {
                // KEY_STARTING_SCOPE maintains a stack of scopes
                @SuppressWarnings("unchecked")
                Deque<Scriptable> stack = (Deque<Scriptable>) cx.getThreadLocal(JavaScriptEngine.KEY_STARTING_SCOPE);
                if (null == stack) {
                    stack = new ArrayDeque<>();
                    cx.putThreadLocal(KEY_STARTING_SCOPE, stack);
                }

                final Object response;
                stack.push(scope_);
                try {
                    cx.putThreadLocal(KEY_STARTING_PAGE, page_);
                    synchronized (page_) { // 2 scripts can't be executed in parallel for one page
                        if (page_ != page_.getEnclosingWindow().getEnclosedPage()) {
                            return null; // page has been unloaded
                        }
                        response = doRun(cx);
                    }
                }
                finally {
                    stack.pop();
                }

                // doProcessPostponedActions is synchronized
                // moved out of the sync block to avoid deadlocks
                if (!holdPostponedActions_) {
                    doProcessPostponedActions();
                }
                return response;
            }
            catch (final Exception e) {
                handleJavaScriptException(new ScriptException(page_, e, getSourceCode(cx)), true);
                return null;
            }
            catch (final TimeoutError e) {
                handleJavaScriptTimeoutError(page_, e);
                return null;
            }
            finally {
                javaScriptRunning_.set(javaScriptAlreadyRunning);
            }
        }

        protected abstract Object doRun(EnhancedContext cx);

        protected abstract String getSourceCode(EnhancedContext cx);
    }


}
