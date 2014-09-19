package org.subshare.rest.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.ApplicationPath;

import org.subshare.rest.server.service.SsBeginPutFileService;
import org.subshare.rest.server.service.SsMakeDirectoryService;
import org.subshare.rest.server.service.SsWebDavService;
import org.subshare.rest.server.service.CryptoChangeSetDtoService;
import org.subshare.rest.server.service.TestSubShareService;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.rest.server.CloudStoreRest;
import co.codewizards.cloudstore.rest.server.service.BeginPutFileService;
import co.codewizards.cloudstore.rest.server.service.MakeDirectoryService;
import co.codewizards.cloudstore.rest.server.service.WebDavService;

@ApplicationPath("SubShareRest")
public class SubShareRest extends CloudStoreRest {
	private static final Logger logger = LoggerFactory.getLogger(SubShareRest.class);

	private static final Map<Class<?>, Class<?>> componentClass2ReplacementComponentClass;

	static {
		logger.debug("<static_init>: Class loaded.");

		final HashMap<Class<?>, Class<?>> m = new HashMap<>();

		m.put(BeginPutFileService.class, SsBeginPutFileService.class);
		m.put(MakeDirectoryService.class, SsMakeDirectoryService.class);
		m.put(WebDavService.class, SsWebDavService.class);

		componentClass2ReplacementComponentClass = Collections.unmodifiableMap(m);
	}

	{
		logger.debug("<init>: Instance created.");

		registerClasses(
				CryptoChangeSetDtoService.class,
				TestSubShareService.class
				);
	}

	@Override
	public ResourceConfig register(final Class<?> componentClass) {
		final Class<?> replacementComponentClass = componentClass2ReplacementComponentClass.get(componentClass);
		if (replacementComponentClass != null)
			return super.register(replacementComponentClass);
		else
			return super.register(componentClass);
	}
}
