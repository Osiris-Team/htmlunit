/*
 * Copyright (c) 2002-2016 Gargoyle Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gargoylesoftware.htmlunit.javascript.host;

import static com.gargoylesoftware.htmlunit.BrowserVersionFeatures.JS_ELEMENT_GET_ATTRIBUTE_RETURNS_EMPTY;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Map;

import com.gargoylesoftware.htmlunit.html.DomAttr;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.javascript.NamedNodeMap;
import com.gargoylesoftware.htmlunit.javascript.host.css.CSSStyleDeclaration2;
import com.gargoylesoftware.htmlunit.javascript.host.css.ComputedCSSStyleDeclaration2;
import com.gargoylesoftware.htmlunit.javascript.host.dom.Node2;
import com.gargoylesoftware.htmlunit.javascript.host.html.HTMLCollection;
import com.gargoylesoftware.js.nashorn.ScriptUtils;
import com.gargoylesoftware.js.nashorn.internal.objects.Global;
import com.gargoylesoftware.js.nashorn.internal.objects.annotations.Getter;
import com.gargoylesoftware.js.nashorn.internal.objects.annotations.Setter;
import com.gargoylesoftware.js.nashorn.internal.runtime.Context;
import com.gargoylesoftware.js.nashorn.internal.runtime.PrototypeObject;
import com.gargoylesoftware.js.nashorn.internal.runtime.ScriptFunction;
import com.gargoylesoftware.js.nashorn.internal.runtime.Source;


public class Element2 extends Node2 {

    private NamedNodeMap attributes_;
    private Map<String, HTMLCollection> elementsByTagName_; // for performance and for equality (==)
    private CSSStyleDeclaration2 style_;

    public static Element2 constructor(final boolean newObj, final Object self) {
        final Element2 host = new Element2();
        host.setProto(Context.getGlobal().getPrototype(host.getClass()));
        return host;
    }

    /**
     * Sets the DOM node that corresponds to this JavaScript object.
     * @param domNode the DOM node
     */
    @Override
    public void setDomNode(final DomNode domNode) {
        super.setDomNode(domNode);
//        style_ = new CSSStyleDeclaration(this);

//        setParentScope(getWindow().getDocument());

        /**
         * Convert JavaScript snippets defined in the attribute map to executable event handlers.
         * Should be called only on construction.
         */
        final DomElement htmlElt = (DomElement) domNode;
        for (final DomAttr attr : htmlElt.getAttributesMap().values()) {
            final String eventName = attr.getName();
            if (eventName.toLowerCase(Locale.ROOT).startsWith("on")) {
                createEventHandler(eventName, attr.getValue());
            }
        }
    }

    /**
     * Callback method which allows different HTML element types to perform custom
     * initialization of computed styles. For example, body elements in most browsers
     * have default values for their margins.
     *
     * @param style the style to initialize
     */
    public void setDefaults(final ComputedCSSStyleDeclaration2 style) {
        // Empty by default; override as necessary.
    }

    /**
     * Create the event handler function from the attribute value.
     * @param eventName the event name (ex: "onclick")
     * @param attrValue the attribute value
     */
    protected void createEventHandler(final String eventName, final String attrValue) {
        final DomElement htmlElt = getDomNodeOrDie();
        // TODO: check that it is an "allowed" event for the browser, and take care to the case
//        final BaseFunction eventHandler = new EventHandler(htmlElt, eventName, attrValue);
        final Source source = Source.sourceFor(eventName + " event for " + htmlElt
                + " in " + htmlElt.getPage().getUrl(), attrValue);
        final ScriptFunction eventHandler = Context.getContext().compileScript(source, Global.instance());
        setEventHandler(eventName, eventHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DomElement getDomNodeOrDie() {
        return (DomElement) super.getDomNodeOrDie();
    }

    /**
     * Returns the style object for this element.
     * @return the style object for this element
     */
    @Getter
    public CSSStyleDeclaration2 getStyle() {
        return style_;
    }

    /**
     * Sets the styles for this element.
     * @param style the style of the element
     */
    @Setter
    public void setStyle(final String style) {
        if (!getBrowserVersion().hasFeature(JS_ELEMENT_GET_ATTRIBUTE_RETURNS_EMPTY)) {
            getStyle().setCssText(style);
        }
    }

    private static MethodHandle staticHandle(final String name, final Class<?> rtype, final Class<?>... ptypes) {
        try {
            return MethodHandles.lookup().findStatic(Element2.class,
                    name, MethodType.methodType(rtype, ptypes));
        }
        catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class FunctionConstructor extends ScriptFunction {
        public FunctionConstructor() {
            super("Element", 
                    staticHandle("constructor", Element2.class, boolean.class, Object.class),
                    null);
            final Prototype prototype = new Prototype();
            PrototypeObject.setConstructor(prototype, this);
            setPrototype(prototype);
        }
    }

    public static final class Prototype extends PrototypeObject {
        Prototype() {
            ScriptUtils.initialize(this);
        }

        public String getClassName() {
            return "Element";
        }
    }
}