package com.example.agent.document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

/**
 * Minimal in-memory {@link MultipartFile} used to feed bytes fetched from the
 * configured source endpoint through the existing ingestion pipeline, which is
 * written against {@code MultipartFile}.
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public InMemoryMultipartFile(String name, String originalFilename,
                                 String contentType, byte[] content) {
        this.name             = name;
        this.originalFilename = originalFilename;
        this.contentType      = contentType;
        this.content          = content != null ? content : new byte[0];
    }

    @Override public String getName()                 { return name; }
    @Override public String getOriginalFilename()     { return originalFilename; }
    @Override public String getContentType()          { return contentType; }
    @Override public boolean isEmpty()                { return content.length == 0; }
    @Override public long getSize()                   { return content.length; }
    @Override public byte[] getBytes()                { return content; }
    @Override public InputStream getInputStream()     { return new ByteArrayInputStream(content); }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, content);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.write(dest.toPath(), content);
    }
}
