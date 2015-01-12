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
import com.github.jinahya.simple.file.back.FileBack.FileOperation;
import com.github.jinahya.simple.file.back.FileBackException;
import com.github.jinahya.simple.file.back.FileContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
public abstract class AbstractLocatorsResource {


    public static final String PREFERRED_PATH_VALUE = "locators";


    @PostConstruct
    private void constructed() {

        logger.debug("fileBack: {}", fileBack);
        logger.debug("fileFronts: {}", fileFronts);
        logger.debug("Header.Accept: {}", accept);

        try {
            tempPath = Files.createTempFile("prefix", "suffix");
            logger.debug("temp path created: {}", tempPath);
        } catch (final IOException ioe) {
            logger.error("failed to create temp path", ioe);
        }
    }


    @PreDestroy
    private void destroying() {

        if (tempPath != null) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (final IOException ioe) {
                logger.error("failed to delete temp path: " + tempPath, ioe);
            }
        }
    }


    /**
     *
     * @param locator the file locator.
     *
     * @return a response.
     *
     * @throws IOException if an I/O error occurs.
     * @throws FileBackException if a file back error occurs.
     */
    @Produces(MediaType.WILDCARD)
    @GET
    @Path("{locator: .+}")
    public Response readSingle(@PathParam("locator") final String locator)
        throws IOException, FileBackException {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.sourceKeySupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(sourceObject -> {
            logger.debug("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.debug("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.debug("target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.debug("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.debug("path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileContext.sourceChannelConsumer(sourceChannel -> {
            logger.debug("source channel : {}", sourceChannel);
            try {
                final long sourceCopied = Files.copy(
                    Channels.newInputStream(sourceChannel), tempPath,
                    StandardCopyOption.REPLACE_EXISTING);
                logger.debug("source copied: {}", sourceCopied);
                sourceCopied_[0] = sourceCopied;
            } catch (final IOException ioe) {
                final String message
                    = "failed from source channel to temp path";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        final FileChannel[] targetChannel_ = new FileChannel[1];
        fileContext.targetChannelSupplier(true ? null : () -> { // _not_usd_!!!
            try {
                targetChannel_[0] = FileChannel.open(
                    tempPath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                logger.debug("target channel: {}", targetChannel_[0]);
                return targetChannel_[0];
            } catch (final IOException ioe) {
                final String message
                    = "failed to open temp path for writing";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        fileBack.operate(fileContext);

        if (sourceCopied_[0] == null) {
            throw new NotFoundException("no file for locator: " + locator);
        }

        return Response
            .ok((StreamingOutput) output -> Files.copy(tempPath, output))
            .header("Content-Length", sourceCopied_[0])
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .build();
    }


    /**
     *
     * @param locator file locator
     * @param distribute distribute flag
     * @param entity the entity to update.
     *
     * @return a response
     *
     * @throws IOException if an I/O error occurs.
     * @throws FileBackException if a file back error occusr.
     * @see FileBack#operate(com.github.jinahya.simple.file.back.FileContext)
     */
    @Consumes(MediaType.WILDCARD)
    @PUT
    @Path("{locator: .+}")
    public Response updateSingle(
        @PathParam("locator") final String locator,
        @QueryParam("distribute")
        @DefaultValue("true") final boolean distribute,
        final InputStream entity) throws IOException, FileBackException {

        try {
            Files.copy(entity, tempPath, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("entity copied to temp path");
        } catch (final IOException ioe) {
            logger.error("failed to copy entity to temp path", ioe);
            throw new WebApplicationException(ioe);
        }

        final FileContext fileContext = new DefaultFileContext();

        fileContext.fileOperationSupplier(() -> FileOperation.WRITE);

        fileContext.targetKeySupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(sourceObject -> {
            logger.debug("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.debug("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.debug("target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[0];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.debug("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.debug("path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileContext.targetChannelConsumer(targetChannel -> {
            logger.debug("target channel : {}", targetChannel);
            try {
                final long targetCopied = Files.copy(
                    tempPath, Channels.newOutputStream(targetChannel));
                logger.debug("target copied: {}", targetCopied);
                sourceCopied_[0] = targetCopied;
            } catch (final IOException ioe) {
                final String message
                    = "failed to copy from temp path to target channel";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        final FileChannel[] sourceChannel_ = new FileChannel[1];
        fileContext.sourceChannelSupplier(true ? null : () -> { // _not_usd_!!!
            try {
                sourceChannel_[0] = FileChannel.open(
                    tempPath, StandardOpenOption.READ);
                logger.debug("source channel: {}", sourceChannel_[0]);
                return sourceChannel_[0];
            } catch (final IOException ioe) {
                final String message
                    = "failed to open temp path for reading";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        fileBack.operate(fileContext);

        // @todo: check fielback outputs

        if (distribute) {
            logger.debug("distributing...");
            final URI baseUri = uriInfo.getBaseUri();
            logger.debug("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.debug("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            for (final URI fileFront : fileFronts) {
                logger.debug("fileFront: {}", fileFront);
                if (!fileFront.isAbsolute()) {
                    logger.warn("not an absolute uri: {}", fileFront);
                    continue;
                }
                if (baseUri.equals(fileFront)) {
                    logger.debug("skipping self: " + fileFront);
                    continue;
                }
                final Client client = ClientBuilder.newClient()
                    .property(ClientProperties.CONNECT_TIMEOUT, 2000)
                    .property(ClientProperties.READ_TIMEOUT, 2000);
                final WebTarget target = client.target(fileFront).path(path)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.debug("target: {}", target.getUri().toString());
//            try {
//                final Response response = target.request().put(
//                    Entity.entity(tempPath.toFile(), contentType));
//                logger.debug("response: {}", response);
//                logger.debug("response.status: {}", response.getStatusInfo());
//            } catch (final ProcessingException pe) {
//                logger.error("failed to distribute to " + fileFront, pe);
//            }
                final Future<Response> future = target.request().async().put(
                    Entity.entity(tempPath.toFile(), contentType));
                logger.debug("future: {]", future);
                futures.add(future);
            }
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.debug("response: {}", response);
                    logger.debug("response.status: {}", response.getStatus());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .build();
    }


    /**
     *
     * @param locator
     * @param distribute
     *
     * @return
     *
     * @throws IOException if an I/O error occurs.
     * @throws FileBackException if a file back error occurs.
     * @see FileBack#operate(com.github.jinahya.simple.file.back.FileContext)
     */
    @DELETE
    @Path("{locator: .+}")
    public Response deleteSingle(
        @PathParam("locator") final String locator,
        @QueryParam("distribute") @DefaultValue("true")
        final boolean distribute)
        throws IOException, FileBackException {

        logger.debug("deleteSingle({}, {})", locator, distribute);

        final FileContext fileContext = new DefaultFileContext();

        fileContext.fileOperationSupplier(() -> FileOperation.DELETE);

        fileContext.targetKeySupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        fileBack.operate(fileContext);

        if (distribute) {
            final URI baseUri = uriInfo.getBaseUri();
            logger.debug("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.debug("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            for (final URI fileFront : fileFronts) {
                logger.debug("fileFront: {}", fileFront);
                if (baseUri.equals(fileFront)) {
                    logger.debug("skipping self: " + fileFront);
                    continue;
                }
                if (!fileFront.isAbsolute()) {
                    logger.warn("not an absolute uri: {}", fileFront);
                    continue;
                }
                final Client client = ClientBuilder.newClient()
                    .property(ClientProperties.CONNECT_TIMEOUT, 1000)
                    .property(ClientProperties.READ_TIMEOUT, 1000);
                final WebTarget target = client.target(fileFront).path(path)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.debug("target: {}", target.getUri().toString());
                try {
                    final Future<Response> future
                        = target.request().async().delete();
                    logger.debug("future: {}", future);
                    futures.add(future);
                } catch (final ProcessingException pe) {
                    logger.error("failed to distribute to " + fileFront, pe);
                }
            }
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.debug("response: {}", response);
                    logger.debug("response.status: {}", response.getStatus());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                }
            });
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
    private MediaType contentType = MediaType.WILDCARD_TYPE;


    @HeaderParam("Accept")
    private String accept;


}

