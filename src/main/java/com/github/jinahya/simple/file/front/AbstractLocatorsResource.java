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
import static java.lang.invoke.MethodHandles.lookup;
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
import static java.util.Optional.ofNullable;
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
import javax.ws.rs.POST;
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


    protected static ByteBuffer key(final String locator) {

        final Logger logger = getLogger(lookup().lookupClass());

        logger.trace("key({})", locator);

        return ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8));
    }


    @PostConstruct
    private void constructed() {

        logger.trace("fileBack: {}", fileBack);
        logger.trace("fileFronts: {}", fileFronts);
        logger.trace("Header.Accept: {}", accept);

        try {
            tempPath = Files.createTempFile("prefix", "suffix");
            logger.trace("temp path created: {}", tempPath);
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


    protected Response copySingle(final FileContext fileContext,
                                  final String sourceLocator,
                                  final String targetLocator,
                                  final boolean distributeFlag)
        throws IOException, FileBackException {

        logger.trace("copySingle({}, {}, {}, {})", fileContext, sourceLocator,
                     targetLocator, distributeFlag);

        fileContext.fileOperationSupplier(() -> FileOperation.COPY);

        fileContext.sourceKeySupplier(
            ofNullable(fileContext.sourceKeySupplier()).orElse(
                () -> key(sourceLocator)));
        fileContext.targetKeySupplier(
            ofNullable(fileContext.targetKeySupplier()).orElse(
                () -> key(targetLocator)));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(
            ofNullable(fileContext.sourceObjectConsumer()).orElse(
                sourceObject -> {
                    logger.trace("consuming source object: {}", sourceObject);
                    sourceObject_[0] = sourceObject;
                }));

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(
            ofNullable(fileContext.sourceCopiedConsumer()).orElse(
                sourceCopied -> {
                    logger.trace("consuming source copied: {}", sourceCopied);
                    sourceCopied_[0] = sourceCopied;
                }));

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(
            ofNullable(fileContext.targetObjectConsumer()).orElse(
                targetObject -> {
                    logger.trace("consuming target object: {}", targetObject);
                    targetObject_[0] = targetObject;
                }));

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(
            ofNullable(fileContext.targetCopiedConsumer()).orElse(
                targetCopied -> {
                    logger.trace("consuming target copied: {}", targetCopied);
                    targetCopied_[0] = targetCopied;
                }));

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(
            ofNullable(fileContext.pathNameConsumer()).orElse(
                pathName -> {
                    logger.trace("consuming path name: {}", pathName);
                    pathName_[0] = pathName;
                }));

        try {
            fileBack.operate(fileContext);
        } catch (IOException | FileBackException e) {
            final String message = "failed to operate file back";
            logger.error(message, e);
            throw new WebApplicationException(message, e);
        }

        if (distributeFlag) {
            final URI baseUri = uriInfo.getBaseUri();
            logger.trace("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.trace("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            for (final URI fileFront : fileFronts) {
                logger.trace("fileFront: {}", fileFront);
                if (baseUri.equals(fileFront)) {
                    logger.trace("skipping self: " + fileFront);
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
                    .queryParam("locator", targetLocator)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.trace("target.uri: {}", target.getUri().toString());
                final Future<Response> future
                    = target.request().async().method("POST");
                logger.trace("future: {}", future);
                futures.add(future);
            }
            logger.trace("futures: {}", futures);
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.trace("response: {}", response);
                    logger.trace("response.statusInfo: {}",
                                 response.getStatusInfo());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
            .build();
    }


    @POST
    @Path("/{locator: .+}/copy")
    public Response copySingle(
        @PathParam("locator") final String sourceLocator,
        @QueryParam("locator") final String targetLocator,
        @QueryParam("distribute") @DefaultValue("true")
        final boolean distribute)
        throws IOException, FileBackException {

        logger.trace("copySingle({}, {}, {})", sourceLocator, targetLocator,
                     distribute);

        final FileContext fileContext = new DefaultFileContext();

        fileContext.fileOperationSupplier(() -> FileOperation.COPY);

        fileContext.sourceKeySupplier(() -> ByteBuffer.wrap(
            sourceLocator.getBytes(StandardCharsets.UTF_8)));
        fileContext.targetKeySupplier(() -> ByteBuffer.wrap(
            targetLocator.getBytes(StandardCharsets.UTF_8)));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(sourceObject -> {
            logger.trace("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.trace("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.trace("target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[0];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.trace("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.trace("path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileBack.operate(fileContext); // ------------------------------ OPERATE

        if (distribute) {
            final URI baseUri = uriInfo.getBaseUri();
            logger.trace("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.trace("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            for (final URI fileFront : fileFronts) {
                logger.trace("fileFront: {}", fileFront);
                if (baseUri.equals(fileFront)) {
                    logger.trace("skipping self: " + fileFront);
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
                    .queryParam("source_locator", sourceLocator)
                    .queryParam("target_locator", targetLocator)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.trace("target.uri: {}", target.getUri().toString());
                try {
                    final Future<Response> future
                        = target.request().async().method("POST");
                    logger.trace("future: {}", future);
                    futures.add(future);
                } catch (final ProcessingException pe) {
                    logger.error("failed to distribute to " + fileFront, pe);
                }
            }
            logger.trace("futures: {}", futures);
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.trace("response: {}", response);
                    logger.trace("response.statusInfo: {}",
                                 response.getStatusInfo());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
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

        logger.trace("deleteSingle({}, {})", locator, distribute);

        final FileContext fileContext = new DefaultFileContext();

        fileContext.fileOperationSupplier(() -> FileOperation.DELETE);

        fileContext.targetKeySupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(sourceObject -> {
            logger.trace("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.trace("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.trace("target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[0];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.trace("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.trace("path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileBack.operate(fileContext); // ------------------------------ OPERATE

        if (distribute) {
            final URI baseUri = uriInfo.getBaseUri();
            logger.trace("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.trace("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            for (final URI fileFront : fileFronts) {
                logger.trace("fileFront: {}", fileFront);
                if (baseUri.equals(fileFront)) {
                    logger.trace("skipping self: " + fileFront);
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
                logger.trace("target: {}", target.getUri().toString());
                try {
                    final Future<Response> future
                        = target.request().async().delete();
                    logger.trace("future: {}", future);
                    futures.add(future);
                } catch (final ProcessingException pe) {
                    logger.error("failed to distribute to " + fileFront, pe);
                }
            }
            logger.trace("futures: {}", futures);
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.trace("response: {}", response);
                    logger.trace("response.statusInfo: {}",
                                 response.getStatusInfo());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get from future", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
            .build();
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
            logger.trace("consuming source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.trace("consuming source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.trace("consuming target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.trace("consuming target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.trace("consuming path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileContext.sourceChannelConsumer(sourceChannel -> {
            logger.trace("consuming source channel : {}", sourceChannel);
            try {
                final long sourceCopied = Files.copy(
                    Channels.newInputStream(sourceChannel), tempPath,
                    StandardCopyOption.REPLACE_EXISTING);
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
                logger.trace("target channel: {}", targetChannel_[0]);
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
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
            .build();
    }


    protected Response updateSingle(final FileContext fileContext,
                                    final String targetLocator,
                                    final InputStream sourceStream,
                                    final boolean distributeFlag) {

        logger.trace("updateSingle({}, {}, {}, {})", fileContext, targetLocator,
                     sourceStream, distributeFlag);

        try {
            Files.copy(sourceStream, tempPath,
                       StandardCopyOption.REPLACE_EXISTING);
            logger.trace("source stream copied to temp path");
        } catch (final IOException ioe) {
            logger.error("failed to copy source stream to temp path", ioe);
            throw new WebApplicationException(ioe);
        }

        fileContext.fileOperationSupplier(() -> FileOperation.WRITE);

        fileContext.targetKeySupplier(() -> key(targetLocator));

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(
            ofNullable(fileContext.sourceObjectConsumer()).orElse(
                sourceObject -> {
                    logger.trace("consuming source object: {}", sourceObject);
                    sourceObject_[0] = sourceObject;
                }));

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(
            ofNullable(fileContext.sourceCopiedConsumer()).orElse(
                sourceCopied -> {
                    logger.trace("consuming source copied: {}", sourceCopied);
                    sourceCopied_[0] = sourceCopied;
                }));

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(
            ofNullable(fileContext.targetObjectConsumer()).orElse(
                targetObject -> {
                    logger.trace("consuming target object: {}", targetObject);
                    targetObject_[0] = targetObject;
                }));

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(
            ofNullable(fileContext.targetCopiedConsumer()).orElse(
                targetCopied -> {
                    logger.trace("consuming target copied: {}", targetCopied);
                    targetCopied_[0] = targetCopied;
                }));

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(
            ofNullable(fileContext.pathNameConsumer()).orElse(
                pathName -> {
                    logger.trace("consuming path name: {}", pathName);
                    pathName_[0] = pathName;
                }));

        fileContext.targetChannelConsumer(targetChannel -> {
            logger.trace("consuming target channel : {}", targetChannel);
            try {
                final long targetCopied = Files.copy(
                    tempPath, Channels.newOutputStream(targetChannel));
                logger.trace("target copied: {}", targetCopied);
                targetCopied_[0] = targetCopied;
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
                logger.trace("suppling source channel: {}", sourceChannel_[0]);
                return sourceChannel_[0];
            } catch (final IOException ioe) {
                final String message
                    = "failed to open temp path for reading";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        try {
            fileBack.operate(fileContext);
        } catch (IOException | FileBackException e) {
            final String message = "failed to operate file back";
            logger.error(message, e);
            throw new WebApplicationException(message, e);
        }

        ofNullable(sourceChannel_[0]).ifPresent(fileChannel -> {
            try {
                fileChannel.close();
                logger.trace("file channel closed: {}", fileChannel);
            } catch (final IOException ioe) {
                final String message
                    = "failed to close file channel: " + fileChannel;
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        if (distributeFlag) {
            logger.trace("distributing...");
            final URI baseUri = uriInfo.getBaseUri();
            logger.trace("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.trace("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            logger.trace("fileFronts: {}", fileFronts);
            for (final URI fileFront : fileFronts) {
                logger.trace("fileFront: {}", fileFront);
                if (!fileFront.isAbsolute()) {
                    logger.warn("not an absolute uri: {}", fileFront);
                    continue;
                }
                if (baseUri.equals(fileFront)) {
                    logger.trace("skipping self: " + fileFront);
                    continue;
                }
                final Client client = ClientBuilder.newClient()
                    .property(ClientProperties.CONNECT_TIMEOUT, 2000)
                    .property(ClientProperties.READ_TIMEOUT, 2000);
                final WebTarget target = client.target(fileFront).path(path)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.trace("target: {}", target.getUri().toString());
//            try {
//                final Response response = target.request().put(
//                    Entity.entity(tempPath.toFile(), contentType));
//                logger.trace("response: {}", response);
//                logger.trace("response.status: {}", response.getStatusInfo());
//            } catch (final ProcessingException pe) {
//                logger.error("failed to distribute to " + fileFront, pe);
//            }
                final Future<Response> future = target.request().async().put(
                    Entity.entity(tempPath.toFile(), contentType));
                logger.trace("future: {}", future);
                futures.add(future);
            }
            logger.trace("futures: {}", futures);
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.trace("response: {}", response);
                    logger.trace("response.status: {}", response.getStatus());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
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
        @QueryParam("distribute") @DefaultValue("true")
        final boolean distribute,
        final InputStream entity)
        throws IOException, FileBackException {

        logger.trace("updateSingle({}, {}, {})", locator, distribute, entity);

        if (true) {
            return updateSingle(new DefaultFileContext(), locator, entity,
                                distribute);
        }

        try {
            Files.copy(entity, tempPath, StandardCopyOption.REPLACE_EXISTING);
            logger.trace("entity copied to temp path");
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
            logger.trace("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.trace("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Object[] targetObject_ = new Object[1];
        fileContext.targetObjectConsumer(targetObject -> {
            logger.trace("target object: {}", targetObject);
            targetObject_[0] = targetObject;
        });

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.trace("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        final String[] pathName_ = new String[1];
        fileContext.pathNameConsumer(pathName -> {
            logger.trace("path name: {}", pathName);
            pathName_[0] = pathName;
        });

        fileContext.targetChannelConsumer(targetChannel -> {
            logger.trace("target channel : {}", targetChannel);
            try {
                final long targetCopied = Files.copy(
                    tempPath, Channels.newOutputStream(targetChannel));
                logger.trace("target copied: {}", targetCopied);
                targetCopied_[0] = targetCopied;
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
                logger.trace("source channel: {}", sourceChannel_[0]);
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
            logger.trace("distributing...");
            final URI baseUri = uriInfo.getBaseUri();
            logger.trace("uriInfo.baseUri: {}", baseUri);
            final String path = uriInfo.getPath();
            logger.trace("uriInfo.path: {}", path);
            final List<Future<Response>> futures = new ArrayList<>();
            logger.trace("fileFronts: {}", fileFronts);
            for (final URI fileFront : fileFronts) {
                logger.trace("fileFront: {}", fileFront);
                if (!fileFront.isAbsolute()) {
                    logger.warn("not an absolute uri: {}", fileFront);
                    continue;
                }
                if (baseUri.equals(fileFront)) {
                    logger.trace("skipping self: " + fileFront);
                    continue;
                }
                final Client client = ClientBuilder.newClient()
                    .property(ClientProperties.CONNECT_TIMEOUT, 2000)
                    .property(ClientProperties.READ_TIMEOUT, 2000);
                final WebTarget target = client.target(fileFront).path(path)
                    .queryParam("distribute", Boolean.FALSE.toString());
                logger.trace("target: {}", target.getUri().toString());
//            try {
//                final Response response = target.request().put(
//                    Entity.entity(tempPath.toFile(), contentType));
//                logger.trace("response: {}", response);
//                logger.trace("response.status: {}", response.getStatusInfo());
//            } catch (final ProcessingException pe) {
//                logger.error("failed to distribute to " + fileFront, pe);
//            }
                final Future<Response> future = target.request().async().put(
                    Entity.entity(tempPath.toFile(), contentType));
                logger.trace("future: {]", future);
                futures.add(future);
            }
            logger.trace("futures: {}", futures);
            futures.forEach(future -> {
                try {
                    final Response response = future.get();
                    logger.trace("response: {}", response);
                    logger.trace("response.status: {}", response.getStatus());
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("fail to get response", e);
                } catch (final Exception e) {
                    logger.error("unhandled", e);
                }
            });
        }

        return Response.noContent()
            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
            .build();
    }


//    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
//    @POST
//    @Path("/urlencoded/update")
//    public Response updateSingleUrlencoded(
//        @FormParam("locator") final String locator,
//        @FormParam("distribute") @DefaultValue("true") final boolean distribute,
//        @FormParam("entity") final InputStream entity)
//        throws IOException, FileBackException {
//
//        logger.trace("updateSingleUrlencoded({}, {}, {})", locator, distribute,
//                     entity);
//
//        if (true) {
//            return updateSingle(new DefaultFileContext(), locator, entity,
//                                distribute);
//        }
//
//        try {
//            Files.copy(entity, tempPath, StandardCopyOption.REPLACE_EXISTING);
//            logger.trace("entity copied to temp path");
//        } catch (final IOException ioe) {
//            logger.error("failed to copy entity to temp path", ioe);
//            throw new WebApplicationException(ioe);
//        }
//
//        final FileContext fileContext = new DefaultFileContext();
//
//        fileContext.fileOperationSupplier(() -> FileOperation.WRITE);
//
//        fileContext.targetKeySupplier(
//            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));
//
//        final Object[] sourceObject_ = new Object[1];
//        fileContext.sourceObjectConsumer(sourceObject -> {
//            logger.trace("source object: {}", sourceObject);
//            sourceObject_[0] = sourceObject;
//        });
//
//        final Long[] sourceCopied_ = new Long[1];
//        fileContext.sourceCopiedConsumer(sourceCopied -> {
//            logger.trace("source copied: {}", sourceCopied);
//            sourceCopied_[0] = sourceCopied;
//        });
//
//        final Object[] targetObject_ = new Object[1];
//        fileContext.targetObjectConsumer(targetObject -> {
//            logger.trace("target object: {}", targetObject);
//            targetObject_[0] = targetObject;
//        });
//
//        final Long[] targetCopied_ = new Long[1];
//        fileContext.targetCopiedConsumer(targetCopied -> {
//            logger.trace("target copied: {}", targetCopied);
//            targetCopied_[0] = targetCopied;
//        });
//
//        final String[] pathName_ = new String[1];
//        fileContext.pathNameConsumer(pathName -> {
//            logger.trace("path name: {}", pathName);
//            pathName_[0] = pathName;
//        });
//
//        fileContext.targetChannelConsumer(targetChannel -> {
//            logger.trace("target channel : {}", targetChannel);
//            try {
//                final long targetCopied = Files.copy(
//                    tempPath, Channels.newOutputStream(targetChannel));
//                logger.trace("target copied: {}", targetCopied);
//                targetCopied_[0] = targetCopied;
//            } catch (final IOException ioe) {
//                final String message
//                    = "failed to copy from temp path to target channel";
//                logger.error(message, ioe);
//                throw new WebApplicationException(message, ioe);
//            }
//        });
//
//        final FileChannel[] sourceChannel_ = new FileChannel[1];
//        fileContext.sourceChannelSupplier(true ? null : () -> { // _not_usd_!!!
//            try {
//                sourceChannel_[0] = FileChannel.open(
//                    tempPath, StandardOpenOption.READ);
//                logger.trace("source channel: {}", sourceChannel_[0]);
//                return sourceChannel_[0];
//            } catch (final IOException ioe) {
//                final String message
//                    = "failed to open temp path for reading";
//                logger.error(message, ioe);
//                throw new WebApplicationException(message, ioe);
//            }
//        });
//
//        fileBack.operate(fileContext);
//
//        // @todo: check fielback outputs
//
//        if (distribute) {
//            logger.trace("distributing...");
//            final URI baseUri = uriInfo.getBaseUri();
//            logger.trace("uriInfo.baseUri: {}", baseUri);
//            final String path = uriInfo.getPath();
//            logger.trace("uriInfo.path: {}", path);
//            final List<Future<Response>> futures = new ArrayList<>();
//            logger.trace("fileFronts: {}", fileFronts);
//            for (final URI fileFront : fileFronts) {
//                logger.trace("fileFront: {}", fileFront);
//                if (!fileFront.isAbsolute()) {
//                    logger.warn("not an absolute uri: {}", fileFront);
//                    continue;
//                }
//                if (baseUri.equals(fileFront)) {
//                    logger.trace("skipping self: " + fileFront);
//                    continue;
//                }
//                final Client client = ClientBuilder.newClient()
//                    .property(ClientProperties.CONNECT_TIMEOUT, 2000)
//                    .property(ClientProperties.READ_TIMEOUT, 2000);
//                final WebTarget target = client.target(fileFront).path(path)
//                    .queryParam("distribute", Boolean.FALSE.toString());
//                logger.trace("target: {}", target.getUri().toString());
////            try {
////                final Response response = target.request().put(
////                    Entity.entity(tempPath.toFile(), contentType));
////                logger.trace("response: {}", response);
////                logger.trace("response.status: {}", response.getStatusInfo());
////            } catch (final ProcessingException pe) {
////                logger.error("failed to distribute to " + fileFront, pe);
////            }
//                final Future<Response> future = target.request().async().put(
//                    Entity.entity(tempPath.toFile(), contentType));
//                logger.trace("future: {]", future);
//                futures.add(future);
//            }
//            logger.trace("futures: {}", futures);
//            futures.forEach(future -> {
//                try {
//                    final Response response = future.get();
//                    logger.trace("response: {}", response);
//                    logger.trace("response.status: {}", response.getStatus());
//                } catch (InterruptedException | ExecutionException e) {
//                    logger.error("fail to get response", e);
//                } catch (final Exception e) {
//                    logger.error("unhandled", e);
//                }
//            });
//        }
//
//        return Response.noContent()
//            .header(FileFrontConstants.HEADER_PATH_NAME, pathName_[0])
//            .header(FileFrontConstants.HEADER_SOURCE_COPIED, sourceCopied_[0])
//            .header(FileFrontConstants.HEADER_TARGET_COPIED, targetCopied_[0])
//            .build();
//    }
    /**
     * Returns the injected backing.
     *
     * @return the injected backing.
     */
    protected FileBack getFileBack() {

        return fileBack;
    }


    /**
     * Returns the injected siblings.
     *
     * @return the injected siblings.
     */
    protected List<URI> getFileFronts() {

        return fileFronts;
    }


    private transient final Logger logger = getLogger(lookup().lookupClass());


    private transient java.nio.file.Path tempPath;


    /**
     * A file back injected.
     */
    @Inject
    @Backing
    private FileBack fileBack;


    /**
     * A list of URIs to distribute files and commands.
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

