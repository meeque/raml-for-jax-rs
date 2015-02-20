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

import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.strip;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

import org.raml.jaxrs.codegen.core.ext.GeneratorExtension;
import org.raml.model.Action;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;

public class ClientGenerator extends AbstractGenerator {

	/**
	 * {@inheritDoc} <br>
	 * This implementation creates a resource client, rather than an interface!
	 */
	@Override
	protected void createResourceInterface(Resource resource, Raml raml)
			throws Exception {

		final String resourceInterfaceName = Names
				.buildResourceInterfaceName(resource);
		final JPackage pkg = context.getCodeModel()._package(
				context.getConfiguration().getBasePackageName() + ".builder");
		final JDefinedClass resourceClient = pkg._class(resourceInterfaceName);
		context.setCurrentResourceInterface(resourceClient);

		final String path = strip(resource.getRelativeUri(), "/");
		resourceClient.field(JMod.PUBLIC | JMod.STATIC | JMod.FINAL,
				String.class, "PATH", JExpr.lit(path));

		if (isNotBlank(resource.getDescription())) {
			resourceClient.javadoc().add(resource.getDescription());
		}

		resourceClient.field(JMod.PRIVATE, Client.class, "_jaxRsClient");
		resourceClient.field(JMod.PRIVATE, URI.class, "_baseUrl");

		final JMethod createRequestTarget = resourceClient.method(
				JMod.PROTECTED, WebTarget.class, "createRequestTarget");
		createRequestTarget.body().directStatement(
				"return _jaxRsClient.target(_baseUrl).path(PATH);");

		addResourceMethods(resource, resourceClient, path);

		/* call registered extensions */
		for (GeneratorExtension e : extensions) {
			e.onCreateResourceInterface(resourceClient, resource);
		}
	}

	@Override
	protected void addResourceMethod(
			JDefinedClass resourceClient,
			String resourceInterfacePath,
			Action action,
			MimeType requestMimeType,
			boolean addBodyMimeTypeInMethodName,
			Collection<MimeType> uniqueResponseMimeTypes) throws Exception {

		final String actionName = action.getType().name().toLowerCase(Locale.ROOT);
		final String methodName = Names.buildResourceMethodName(action, addBodyMimeTypeInMethodName ? requestMimeType : null);

		if (uniqueResponseMimeTypes.isEmpty())
		{
			final JMethod resourceMethod = resourceClient.method(JMod.PUBLIC, Void.TYPE, methodName);

			if(requestMimeType != null)
			{
				addBodyParameters(requestMimeType, resourceMethod, new JDocComment(context.getCodeModel()));
				resourceMethod.body().directStatement("createRequestTarget().request()." + actionName + "(javax.ws.rs.client.Entity.entity(entity, \"" + requestMimeType.getType() + "\"));"); 
			}
			else
			{
				resourceMethod.body().directStatement("createRequestTarget().request()." + actionName + "();"); 
			}
		}
		else
		{
			// obtain return type of resource
			final JType methodReturnType = types.getResponseEntityClass(uniqueResponseMimeTypes.iterator().next());
			final JMethod resourceMethod = resourceClient.method(JMod.PUBLIC, methodReturnType, methodName);
			
			String methodReturnTypeLiteral = methodReturnType.name() + ".class";
			if(methodReturnType instanceof JClass)
			{
				final JClass methodReturnClass = (JClass) methodReturnType;
				if(methodReturnClass.isParameterized())
				{
					methodReturnTypeLiteral = "new javax.ws.rs.core.GenericType<" + methodReturnType.name() + ">(){}";
					
				}
				
			}

			if(requestMimeType != null)
			{
				addBodyParameters(requestMimeType, resourceMethod, new JDocComment(context.getCodeModel()));
				resourceMethod.body().directStatement("return createRequestTarget().request()." + actionName + "(javax.ws.rs.client.Entity.entity(entity, \"" + requestMimeType.getType() + "\"), " + methodReturnTypeLiteral +");");
			}
			else
			{
				resourceMethod.body().directStatement("return createRequestTarget().request()." + actionName + "(" + methodReturnTypeLiteral +");");
			}
		}
	}

}
