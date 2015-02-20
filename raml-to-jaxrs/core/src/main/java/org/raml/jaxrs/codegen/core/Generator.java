/*
 * Copyright 2013-2015 (c) MuleSoft, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.raml.jaxrs.codegen.core;

import static com.sun.codemodel.JMod.PUBLIC;
import static com.sun.codemodel.JMod.STATIC;
import static org.apache.commons.lang.StringUtils.capitalize;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.strip;
import static org.raml.jaxrs.codegen.core.Constants.RESPONSE_HEADER_WILDCARD_SYMBOL;
import static org.raml.jaxrs.codegen.core.Names.EXAMPLE_PREFIX;
import static org.raml.jaxrs.codegen.core.Names.GENERIC_PAYLOAD_ARGUMENT_NAME;
import static org.raml.jaxrs.codegen.core.Names.MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.ws.rs.Path;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.raml.jaxrs.codegen.core.ext.GeneratorExtension;
import org.raml.model.Action;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.Response;
import org.raml.model.Template;
import org.raml.model.parameter.AbstractParam;
import org.raml.model.parameter.Header;

import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;

public class Generator extends AbstractGenerator
{
    

    protected void createResourceInterface(final Resource resource, final Raml raml) throws Exception
    {
        final String resourceInterfaceName = Names.buildResourceInterfaceName(resource);
        final JDefinedClass resourceInterface = context.createResourceInterface(resourceInterfaceName);
        context.setCurrentResourceInterface(resourceInterface);

        final String path = strip(resource.getRelativeUri(), "/");
        resourceInterface.annotate(Path.class).param(DEFAULT_ANNOTATION_PARAMETER,
            StringUtils.defaultIfBlank(path, "/"));

        if (isNotBlank(resource.getDescription()))
        {
            resourceInterface.javadoc().add(resource.getDescription());
        }
        
        addResourceMethods(resource, resourceInterface, path);
        
        /* call registered extensions */
        for (GeneratorExtension e : extensions) {
        	e.onCreateResourceInterface(resourceInterface, resource);
        }        
    }

    

    protected void addResourceMethod(final JDefinedClass resourceInterface,
                                   final String resourceInterfacePath,
                                   final Action action,
                                   final MimeType bodyMimeType,
                                   final boolean addBodyMimeTypeInMethodName,
                                   final Collection<MimeType> uniqueResponseMimeTypes) throws Exception
    {
        final String methodName = Names.buildResourceMethodName(action,
            addBodyMimeTypeInMethodName ? bodyMimeType : null);

        Configuration configuration = context.getConfiguration();
        String asyncResourceTrait = configuration.getAsyncResourceTrait();
        boolean asyncMethod = isNotBlank(asyncResourceTrait) && action.getIs().contains(asyncResourceTrait);
        
        final JType resourceMethodReturnType = getResourceMethodReturnType(methodName, action,
            uniqueResponseMimeTypes.isEmpty(),asyncMethod, resourceInterface);

        // the actually created unique method name should be needed in the previous method but
        // no way of doing this :(
        final JMethod method = context.createResourceMethod(resourceInterface, methodName,
            resourceMethodReturnType);
        
        if (configuration.getMethodThrowException() != null ) {
            method._throws(configuration.getMethodThrowException());
        }

        context.addHttpMethodAnnotation(action.getType().toString(), method);

        addParamAnnotation(resourceInterfacePath, action, method);
        addConsumesAnnotation(bodyMimeType, method);
        addProducesAnnotation(uniqueResponseMimeTypes, method);

        final JDocComment javadoc = addBaseJavaDoc(action, method);

        final Map<String, JType> templateParameterTypes = getTemplateParameterClasses(resourceInterface, action);
        for (final Entry<String, JType> templateParameterType : templateParameterTypes.entrySet()) {
			method.param(templateParameterType.getValue(), templateParameterType.getKey());
		}
        
        addPathParameters(action, method, javadoc);
        addHeaderParameters(action, method, javadoc);
        addQueryParameters(action, method, javadoc);
        addBodyParameters(bodyMimeType, method, javadoc);
        if (asyncMethod) {
            addAsyncResponseParameter(asyncResourceTrait, method, javadoc);
        }
        
        /* call registered extensions */
        for (GeneratorExtension e : extensions) {
        	e.onAddResourceMethod(method, action, bodyMimeType, uniqueResponseMimeTypes);
        }
    }

    protected Map<String, AbstractParam> getAllParameters(Action action) {

        final Map<String, AbstractParam> parameters = new LinkedHashMap<String, AbstractParam>();

        for ( final Entry<String, Header> header : action.getHeaders().entrySet() )
        {
    		parameters.put(header.getKey(), header.getValue());
        }
        
        // TODO collect other types of params (url, etc)
        
        return parameters;
	}

	protected Map<String, JType> getTemplateParameterClasses(final JDefinedClass resourceInterface,
                                   final Action action) throws Exception
    {
    	final Map<String, JType> templateParameters = new LinkedHashMap<String, JType>();
    	
    	final String resourceTypeName = action.getResource().getType();
    	final Set<String> traitNames = new LinkedHashSet<String>();
    	traitNames.addAll(action.getResource().getIs());
    	traitNames.addAll(action.getIs());
    	
    	// TODO this is global for the whole raml, so stash it in the context or something
    	final Map<String, Template> availableResourceTypes = flattenTemplates(context.getRaml().getResourceTypes());
    	final Map<String, Template> availableTraits = flattenTemplates(context.getRaml().getTraits());
    	
    	final Template resourceType = availableResourceTypes.get(resourceTypeName);
    	if(resourceType != null)
    	{
    		templateParameters.put(
    				Names.buildVariableName(resourceTypeName),
    				createTemplateParameterClass(resourceTypeName, resourceType, false));
    	}
    	
    	final Map<String, Template> traits = new LinkedHashMap<String, Template>(availableTraits);
    	traits.keySet().retainAll(traitNames);
    	for(final Entry<String, Template> trait : traits.entrySet())
    	{
    		templateParameters.put(
    				Names.buildVariableName(trait.getKey()),
    				createTemplateParameterClass(trait.getKey(), trait.getValue(), true));
    	}
    	
    	return templateParameters;
    }
	
    /**
     * 
     */
    private static Map<String, Template> flattenTemplates(final List<Map<String, Template>> templates)
    {
    	final Map<String, Template> flattenedTemplates = new LinkedHashMap<String, Template>();
    	for (Map<String, Template> template : templates) {
			flattenedTemplates.putAll(template);
		}
    	return flattenedTemplates;
    }

    private JType getResourceMethodReturnType(final String methodName,
                                              final Action action,
                                              final boolean returnsVoid,
                                              final boolean asyncMethod,
                                              final JDefinedClass resourceInterface) throws Exception
    {
    	if (asyncMethod) 
    	{
    	   // returns void but also generate the response helper object
    	   createResourceMethodReturnType(methodName, action, resourceInterface);
    	   return types.getGeneratorType(void.class);
    	}
    	else
        if (returnsVoid&&context.getConfiguration().isEmptyResponseReturnVoid())
        {
            return types.getGeneratorType(void.class);
        }
        else
        {
            return createResourceMethodReturnType(methodName, action, resourceInterface);
        }
    }
    private void addAsyncResponseParameter(String asyncResourceTrait,final JMethod method,final JDocComment javadoc) throws Exception {
    	
      final String argumentName = Names.buildVariableName(asyncResourceTrait);
    	
      final JVar argumentVariable = method.param(types.getGeneratorClass("javax.ws.rs.container.AsyncResponse"),
      argumentName);
    
      argumentVariable.annotate(types.getGeneratorClass("javax.ws.rs.container.Suspended"));
      javadoc.addParam( argumentVariable.name()).add(asyncResourceTrait);
   }

    private JDefinedClass createResourceMethodReturnType(final String methodName,
                                                         final Action action,
                                                         final JDefinedClass resourceInterface)
        throws Exception
    {
        final JDefinedClass responseClass = resourceInterface._class(capitalize(methodName) + "Response")
            ._extends(context.getResponseWrapperType());

        final JMethod responseClassConstructor = responseClass.constructor(JMod.PRIVATE);
        responseClassConstructor.param(javax.ws.rs.core.Response.class, "delegate");
        responseClassConstructor.body().invoke("super").arg(JExpr.ref("delegate"));

        for (final Entry<String, Response> statusCodeAndResponse : action.getResponses().entrySet())
        {
            createResponseBuilderInResourceMethodReturnType(action, responseClass, statusCodeAndResponse);
        }

        return responseClass;
    }

    private void createResponseBuilderInResourceMethodReturnType(final Action action,
                                                                 final JDefinedClass responseClass,
                                                                 final Entry<String, Response> statusCodeAndResponse)
        throws Exception
    {
        final int statusCode = NumberUtils.toInt(statusCodeAndResponse.getKey());
        final Response response = statusCodeAndResponse.getValue();

        if (!response.hasBody())
        {
            createResponseBuilderInResourceMethodReturnType(responseClass, statusCode, response, null);
        }
        else
        {
            for (final MimeType mimeType : response.getBody().values())
            {
                createResponseBuilderInResourceMethodReturnType(responseClass, statusCode, response, mimeType);
            }
        }
    }

    private void createResponseBuilderInResourceMethodReturnType(final JDefinedClass responseClass,
                                                                 final int statusCode,
                                                                 final Response response,
                                                                 final MimeType responseMimeType)
        throws Exception
    {
        final String responseBuilderMethodName = Names.buildResponseMethodName(statusCode, responseMimeType);

        final JMethod responseBuilderMethod = responseClass.method(PUBLIC + STATIC, responseClass,
            responseBuilderMethodName);

        final JDocComment javadoc = responseBuilderMethod.javadoc();

        if (isNotBlank(response.getDescription()))
        {
            javadoc.add(response.getDescription());
        }

        if ((responseMimeType != null) && (isNotBlank(responseMimeType.getExample())))
        {
            javadoc.add(EXAMPLE_PREFIX + responseMimeType.getExample());
        }

        JInvocation builderArgument = types.getGeneratorClass(javax.ws.rs.core.Response.class)
            .staticInvoke("status")
            .arg(JExpr.lit(statusCode));

        if (responseMimeType != null)
        {
            builderArgument = builderArgument.invoke("header")
                .arg(HttpHeaders.CONTENT_TYPE)
                .arg(responseMimeType.getType());
        }

        final StringBuilder freeFormHeadersDescription = new StringBuilder();
        
        for (final Entry<String, Header> namedHeaderParameter : response.getHeaders().entrySet())
        {
            final String headerName = namedHeaderParameter.getKey();
            final Header header = namedHeaderParameter.getValue();

            if (headerName.contains(RESPONSE_HEADER_WILDCARD_SYMBOL))
            {
                appendParameterJavadocDescription(header, freeFormHeadersDescription);
                continue;
            }

            final String argumentName = Names.buildVariableName(headerName);
            if (!header.isRepeat()){
            	builderArgument = builderArgument.invoke("header").arg(headerName).arg(JExpr.ref(argumentName));
            }
            addParameterJavaDoc(header, argumentName, javadoc);

            responseBuilderMethod.param(types.buildParameterType(header, argumentName), argumentName);
        }

        final JBlock responseBuilderMethodBody = responseBuilderMethod.body();

        final JVar builderVariable = responseBuilderMethodBody.decl(
            types.getGeneratorType(ResponseBuilder.class), "responseBuilder", builderArgument);

        if (freeFormHeadersDescription.length() > 0)
        {
            // generate a Map<String, List<Object>> argument for {?} headers
            final JClass listOfObjectsClass = types.getGeneratorClass(List.class).narrow(Object.class);
            final JClass headersArgument = types.getGeneratorClass(Map.class).narrow(
                types.getGeneratorClass(String.class), listOfObjectsClass);

            builderArgument = responseBuilderMethodBody.invoke("headers")
                .arg(JExpr.ref(MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME))
                .arg(builderVariable);

            final JVar param = responseBuilderMethod.param(headersArgument,
                MULTIPLE_RESPONSE_HEADERS_ARGUMENT_NAME);

            javadoc.addParam(param).add(freeFormHeadersDescription.toString());
        }

        if (responseMimeType != null)
        {
            responseBuilderMethodBody.invoke(builderVariable, "entity").arg(
                JExpr.ref(GENERIC_PAYLOAD_ARGUMENT_NAME));
            responseBuilderMethod.param(types.getResponseEntityClass(responseMimeType),
                GENERIC_PAYLOAD_ARGUMENT_NAME);
            javadoc.addParam(GENERIC_PAYLOAD_ARGUMENT_NAME).add(defaultString(responseMimeType.getExample()));
        }
        for (final Entry<String, Header> namedHeaderParameter : response.getHeaders().entrySet())
        {
            final String headerName = namedHeaderParameter.getKey();
            final Header header = namedHeaderParameter.getValue();

            final String argumentName = Names.buildVariableName(headerName);
            if (header.isRepeat()){
            	JBlock body = responseBuilderMethod.body().forEach(context.getGeneratorType(Types.getJavaType(header)), "h", JExpr.ref(argumentName)).body();
				body.add(JExpr.invoke(JExpr.ref("responseBuilder"), "header").arg(headerName).arg(JExpr.ref("h")));
            }            
        }
        responseBuilderMethodBody._return(JExpr._new(responseClass).arg(builderVariable.invoke("build")));
    }

    

        
    
    

}
