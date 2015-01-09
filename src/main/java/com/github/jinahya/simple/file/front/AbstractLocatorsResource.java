/*
 * Copyright 2014 Jin Kwon.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.github.jinahya.simple.file.front;


import com.github.jinahya.simple.file.back.DefaultFileContext;
import com.github.jinahya.simple.file.back.FileBack;
import com.github.jinahya.simple.file.back.FileBackException;
import com.github.jinahya.simple.file.back.FileContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import static java.lang.invoke.MethodHandles.lookup;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
//@Path("locators")
public abstract class AbstractLocatorsResource {


    public static final String PREFERRED_PATH_VALUE = "locators";


    private static final String PROPERTY_PREFIX
        = "http://jinahya.github.com/simple/file/front";


    private static final String PROPERTY_TEMP_PATH
        = PROPERTY_PREFIX + "/temp_path";


    private static final String PROPERTY_MEDIA_TYPE
        = PROPERTY_PREFIX + "/media_type";


    public static void updateSingleDistribute(final FileContext fileContext,
                                              final UriInfo uriInfo,
                                              final List<URI> fileFronts) {

        final Logger logger = getLogger(lookup().lookupClass());

        final URI baseUri = uriInfo.getBaseUri();
        logger.debug("baseUri: {}", baseUri);
        final String path = uriInfo.getPath();
        logger.debug("path: {}", path);

        final java.nio.file.Path tempPath = fileContext.property(
            PROPERTY_TEMP_PATH, java.nio.file.Path.class).orElse(null);
        logger.debug("tempPath: {}", tempPath);
        if (tempPath == null) {
            logger.error("no tempPath supplied");
            return;
        }
        final MediaType mediaType = fileContext.property(
            PROPERTY_MEDIA_TYPE, MediaType.class)
            .orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        logger.debug("mediaType: {}", mediaType);

        for (final URI fileFront : fileFronts) {
            logger.debug("fileFront: {}", fileFront);
            if (baseUri.equals(fileFront)) {
                logger.debug("skipping self: " + fileFront);
                continue;
            }
            final Client client = ClientBuilder.newClient();
            WebTarget target = client.target(fileFront).path(path)
                .queryParam("distribute", Boolean.FALSE.toString());
            final String suffix = Optional.ofNullable(
                fileContext.fileSuffixSupplier()).orElse(() -> null)
                .get();
            if (suffix != null && !suffix.trim().isEmpty()) {
                target = target.queryParam("suffix", suffix);
            }
            logger.debug("target: {}", target.getUri().toString());
            try {
                final Entity<File> entity
                    = Entity.entity(tempPath.toFile(), mediaType);
                final Response response = target.request().put(entity);
                logger.debug("status: {}", response.getStatusInfo());
            } catch (ProcessingException pe) {
                logger.error("failed to distribute to " + fileFront, pe);
            }
        }
    }


    public static void deleteSingleDistribute(final FileContext fileContext,
                                              final UriInfo uriInfo,
                                              final List<URI> fileFronts) {

        final Logger logger = getLogger(lookup().lookupClass());

        logger.debug("deleteSingleDistribute({}, {}, {})", fileContext, uriInfo,
                     fileFronts);

        final URI baseUri = uriInfo.getBaseUri();
        logger.debug("uriInfo.baseUri: {}", baseUri);
        final String path = uriInfo.getPath();
        logger.debug("uriInfo.path: {}", path);

        for (final URI fileFront : fileFronts) {
            logger.debug("fileFront: {}", fileFront);
            if (baseUri.equals(fileFront)) {
                logger.debug("skipping self: " + fileFront);
                continue;
            }
            final Client client = ClientBuilder.newClient()
                .property(ClientProperties.CONNECT_TIMEOUT, 1000)
                .property(ClientProperties.READ_TIMEOUT, 1000);
            WebTarget target = client.target(fileFront).path(path)
                .queryParam("distribute", Boolean.FALSE.toString());
            final String suffix = Optional.ofNullable(
                fileContext.fileSuffixSupplier()).orElse(() -> null)
                .get();
            if (suffix != null && !suffix.trim().isEmpty()) {
                target = target.queryParam("suffix", suffix);
            }
            logger.debug("target: {}", target.getUri().toString());
            try {
                final Response response = target.request().delete();
                logger.debug("status: {}", response.getStatusInfo());
            } catch (final ProcessingException pe) {
                logger.error("failed to distribute to " + fileFront, pe);
            }
        }
    }


    @PostConstruct
    private void constructed() {
    }


    @PreDestroy
    private void destroying() {

        if (tempPath != null) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (final IOException ioe) {
                logger.error("failed to delete tempPath: " + tempPath, ioe);
            }
        }
    }


    /**
     * Reads file content mapped to specified {@code locator}.
     *
     * @param locator the locator of file.
     * @param suffix an optional file name suffix
     *
     * @return a response.
     */
    @Produces(MediaType.WILDCARD)
    @GET
    @Path("{locator: .+}")
    public Response readSingle(
        @PathParam("locator") final String locator,
        @QueryParam("suffix") final String suffix) {

        logger.debug("Header.Accept: {}", accept);

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        if (suffix != null && !suffix.trim().isEmpty()) {
            fileContext.fileSuffixSupplier(() -> suffix.trim());
        }

        fileContext.targetChannelSupplier(() -> {
            try {
                tempPath = Files.createTempFile("prefix", "suffix");
                return FileChannel.open(tempPath, StandardOpenOption.READ,
                                        StandardOpenOption.WRITE);
            } catch (final IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });

        final Holder<Long> targetCopiedHolder = new Holder<>();
        fileContext.targetCopiedConsumer(targetCopied -> {
            targetCopiedHolder.value(targetCopied);
        });

        final Holder<String> pathNameHolder = new Holder<>();
        fileContext.pathNameConsumer(pathName -> {
            pathNameHolder.value(pathName);
        });

        try {
            fileBack.read(fileContext);
        } catch (final IOException | FileBackException e) {
            logger.error("failed to read", e);
            throw new WebApplicationException(e); // 500
        }

        if (targetCopiedHolder.value() == null) {
            logger.error("targetCopied -> null");
            throw new NotFoundException(); // 404
        }

        return Response.ok((StreamingOutput) output -> {
            Files.copy(tempPath, output);
        })
            .header("Content-Length", targetCopiedHolder.value())
            .header(FileFrontConstants.HEADER_PATH_NAME, pathNameHolder.value())
            .build();
    }


    /**
     * Updates a file content located by specified {@code locator}.
     *
     * @param locator the locator of file.
     * @param suffix file suffix
     * @param distribute a flag for distributing to siblings.
     * @param entity
     *
     * @return a response.
     */
    @Consumes(MediaType.WILDCARD)
    @PUT
    @Path("{locator: .+}")
    public Response updateSingle(
        @PathParam("locator") final String locator,
        @QueryParam("suffix") final String suffix,
        @QueryParam("distribute")
        @DefaultValue("true") final boolean distribute,
        final InputStream entity) {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        if (suffix != null && !suffix.trim().isEmpty()) {
            fileContext.fileSuffixSupplier(() -> suffix.trim());
        }

        final Holder<String> pathNameHolder = new Holder<>();
        fileContext.pathNameConsumer(pathName -> {
            pathNameHolder.value(pathName);
        });

        try {
            tempPath = Files.createTempFile("prefix", "suffix");
            Files.copy(entity, tempPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ioe) {
            throw new WebApplicationException(ioe);
        }

        fileContext.sourceChannelSupplier(() -> {
            try {
                return FileChannel.open(tempPath, StandardOpenOption.READ);
            } catch (final IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });

        final Holder<Long> sourceCopiedHolder = new Holder<>();
        fileContext.sourceCopiedConsumer(
            sourceCopied -> sourceCopiedHolder.value(sourceCopied)
        );

        try {
            fileBack.update(fileContext);
        } catch (IOException | FileBackException e) {
            logger.error("failed to write", e);
            throw new WebApplicationException(e); // 500
        }

        if (distribute) {
            logger.debug("distrubuting...");
            fileContext.property(PROPERTY_TEMP_PATH, tempPath);
            fileContext.property(PROPERTY_MEDIA_TYPE, contentType);
            updateSingleDistribute(fileContext, uriInfo, fileFronts);
        }

        return Response.noContent().build();
    }


    /**
     * Deletes a file content located by given {@code locator}.
     *
     * @param locator the file locator
     * @param suffix an optional file name suffix such as "png" or "txt"
     * @param distribute
     *
     * @return response
     */
    @DELETE
    @Path("{locator: .+}")
    public Response deleteSingle(
        @PathParam("locator") final String locator,
        @QueryParam("suffix") final String suffix,
        @QueryParam("distribute") @DefaultValue("true")
        final boolean distribute) {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        if (suffix != null && !suffix.trim().isEmpty()) {
            fileContext.fileSuffixSupplier(() -> suffix.trim());
        }

        try {
            fileBack.delete(fileContext);
        } catch (IOException | FileBackException e) {
            logger.error("failed to delete", e);
            throw new WebApplicationException(e);
        }

        if (distribute) {
            logger.debug("distributing..");
            deleteSingleDistribute(fileContext, uriInfo, fileFronts);
        }

        return Response.noContent().build();
    }


    private transient final Logger logger = getLogger(getClass());


    private transient java.nio.file.Path tempPath;


    /**
     * A file back injected.
     */
    @Inject
    @Backing
    private FileBack fileBack;


    /**
     * A list of sibling file fronts to distribute files and commands.
     */
    @Inject
    @Siblings
    private List<URI> fileFronts;


    @Context
    private UriInfo uriInfo;


    @HeaderParam("Content-Type")
    private MediaType contentType;


    @HeaderParam("Accept")
    private String accept;


}

