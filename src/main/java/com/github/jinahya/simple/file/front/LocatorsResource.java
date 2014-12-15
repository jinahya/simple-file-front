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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
@Path("/locators")
public class LocatorsResource {


    /**
     * Reads file content mapped to specified {@code locator}.
     *
     * @param locator the locator of file.
     *
     * @return a response.
     */
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @GET
    @Path("/{locator: .+}")
    public StreamingOutput readSingle(
        @PathParam("locator") final String locator) {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        final Mutable<java.nio.file.Path> localPathHolder
            = new MutableObject<>();
        fileContext.localPathConsumer(localPath -> {
            localPathHolder.setValue(localPath);
            servletResponse.setStatus(
                Files.isRegularFile(localPath)
                ? HttpServletResponse.SC_OK : HttpServletResponse.SC_NOT_FOUND);
        });

        final Mutable<String> pathNameHolder = new MutableObject<>();
        fileContext.pathNameConsumer(pathName -> {
            servletResponse.setHeader(
                FileFrontConstants.HEADER_PATH_NAME, pathName);
            pathNameHolder.setValue(pathName);
        });

        final MutableLong bytesCopiedHolder = new MutableLong();
        fileContext.bytesCopiedConsumer(bytesCopied -> {
            bytesCopiedHolder.setValue(bytesCopied);
            servletResponse.setHeader(
                FileFrontConstants.HEADER_BYTES_COPIED,
                Long.toString(bytesCopied));
        });

        return targetStream -> {
            fileContext.targetChannelSupplier(
                () -> Channels.newChannel(targetStream));
            try {
                fileBack.read(fileContext);
            } catch (final FileBackException fbe) {
                throw new WebApplicationException(fbe);
            }
        };
    }


    /**
     * Updates a file content mapped to specified {@code locator}.
     *
     * @param locator the locator of file.
     *
     * @return a response.
     */
    @PUT
    @Path("/{locator: .+}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response updateSingle(
        @PathParam("locator") final String locator) {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        final Mutable<java.nio.file.Path> localPathHolder
            = new MutableObject<>();
        fileContext.localPathConsumer(localPath -> {
            localPathHolder.setValue(localPath);
//            servletResponse.setStatus(
//                Files.isRegularFile(localPath)
//                ? HttpServletResponse.SC_OK : HttpServletResponse.SC_CREATED);
        });

        final Mutable<String> pathNameHolder = new MutableObject<>();
        fileContext.pathNameConsumer(pathName -> {
            pathNameHolder.setValue(pathName);
            servletResponse.setHeader(
                FileFrontConstants.HEADER_PATH_NAME, pathName);
        });

        fileContext.sourceChannelSupplier(() -> {
            try {
                return Channels.newChannel(servletRequest.getInputStream());
            } catch (final IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });

        final MutableLong bytesCopiedHolder = new MutableLong();
        fileContext.bytesCopiedConsumer(
            bytesCopied -> bytesCopiedHolder.setValue(bytesCopied)
        );

        try {
            fileBack.write(fileContext);
        } catch (final IOException | FileBackException e) {
            throw new WebApplicationException(e); // 500
        }

        return Response.noContent().build();
    }


    @DELETE
    @Path("/{locator: .+}")
    public Response deleteSingle(@PathParam("locator") final String locator) {

        final FileContext fileContext = new DefaultFileContext();

        fileContext.keyBufferSupplier(
            () -> ByteBuffer.wrap(locator.getBytes(StandardCharsets.UTF_8)));

        try {
            fileBack.delete(fileContext);
        } catch (final IOException | FileBackException e) {
            throw new WebApplicationException(e);
        }

        return Response.noContent().build();
    }


    private final transient Logger logger = getLogger(getClass());


    @Inject
    protected FileBack fileBack;


    @Context
    protected HttpServletRequest servletRequest;


    @Context
    protected HttpServletResponse servletResponse;


    @Context
    protected UriInfo uriInfo;


}

