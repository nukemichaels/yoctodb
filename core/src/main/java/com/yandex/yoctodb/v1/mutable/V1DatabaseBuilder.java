/*
 * (C) YANDEX LLC, 2014-2015
 *
 * The Source Code called "YoctoDB" available at
 * https://bitbucket.org/yandex/yoctodb is subject to the terms of the
 * Mozilla Public License, v. 2.0 (hereinafter referred to as the "License").
 *
 * A copy of the License is also available at http://mozilla.org/MPL/2.0/.
 */

package com.yandex.yoctodb.v1.mutable;

import com.google.common.primitives.Ints;
import com.yandex.yoctodb.DatabaseFormat;
import com.yandex.yoctodb.mutable.DatabaseBuilder;
import com.yandex.yoctodb.mutable.DocumentBuilder;
import com.yandex.yoctodb.util.MessageDigestOutputStreamWrapper;
import com.yandex.yoctodb.util.OutputStreamWritable;
import com.yandex.yoctodb.util.UnsignedByteArray;
import com.yandex.yoctodb.v1.V1DatabaseFormat;
import com.yandex.yoctodb.v1.mutable.segment.*;
import net.jcip.annotations.NotThreadSafe;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * {@link DatabaseBuilder} implementation in V1 format
 *
 * @author incubos
 */
@NotThreadSafe
public final class V1DatabaseBuilder
        extends Freezable
        implements DatabaseBuilder {
    private int currentDocumentId = 0;

    private final V1PayloadSegment payloads = new V1PayloadSegment();

    private final Map<String, IndexSegment> indexes =
            new HashMap<String, IndexSegment>();

    @NotNull
    @Override
    public DatabaseBuilder merge(
            @NotNull
            final DocumentBuilder document) {
        checkNotFrozen();

        assert document instanceof V1DocumentBuilder :
                "Wrong document builder implementation supplied";

        final V1DocumentBuilder builder = (V1DocumentBuilder) document;

        // Checking all the necessary fields
        builder.check();

        // Marking document as built
        builder.markBuilt();

        // Updating the indexes

        // pass fixed or variable to index

        for (Map.Entry<String, Collection<UnsignedByteArray>> e :
                builder.fields.asMap().entrySet()) {
            final String fieldName = e.getKey();
            if (fieldName.isEmpty())
                throw new IllegalArgumentException("Empty field name");

            final Collection<UnsignedByteArray> values = e.getValue();
            if (values.isEmpty())
                throw new IllegalArgumentException("Empty values");

            final IndexSegment existingIndex = indexes.get(fieldName);
            if (existingIndex == null) {
                final IndexSegment index;
                @NotNull
                final DocumentBuilder.IndexOption indexOption =
                        builder.index.get(fieldName);
                @NotNull
                final DocumentBuilder.LengthOption lengthOption =
                        builder.length.get(fieldName);

                switch (indexOption) {
                    case FILTERABLE:
                        index = new V1FilterableIndex(
                                fieldName,
                                lengthOption == DocumentBuilder.LengthOption.FIXED
                        );
                        break;
                    case FILTERABLE_TRIE_BASED:
                        index = new V1TrieBasedFilterableIndex(
                                fieldName
                        );
                        break;
                    case SORTABLE:
                        index = new V1FullIndex(
                                fieldName,
                                lengthOption == DocumentBuilder.LengthOption.FIXED
                        );
                        break;
                    case FULL:
                        index = new V1FullIndex(
                                fieldName,
                                lengthOption == DocumentBuilder.LengthOption.FIXED
                        );
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "Unsupported index option: " + indexOption);
                }
                indexes.put(fieldName, index);
                index.addDocument(currentDocumentId, values);
            } else {
                existingIndex.addDocument(currentDocumentId, values);
            }
        }

        // Adding payload and moving on
        payloads.addDocument(currentDocumentId, builder.payload);
        currentDocumentId++;

        return this;
    }

    @NotNull
    @Override
    public OutputStreamWritable buildWritable() {
        freeze();

        // Build writables
        final List<OutputStreamWritable> writables =
                new ArrayList<OutputStreamWritable>(indexes.size() + 1);
        for (IndexSegment segment : indexes.values()) {
            segment.setDatabaseDocumentsCount(currentDocumentId);
            writables.add(segment.buildWritable());
        }
        writables.add(payloads.buildWritable());

        return new OutputStreamWritable() {
            @Override
            public long getSizeInBytes() {
                long size =
                        V1DatabaseFormat.MAGIC.length +
                        Ints.BYTES + // Format length
                        V1DatabaseFormat.DIGEST_SIZE_IN_BYTES;

                for (OutputStreamWritable writable : writables) {
                    size += writable.getSizeInBytes();
                }

                return size;
            }

            @Override
            public void writeTo(
                    @NotNull
                    final OutputStream os) throws IOException {
                // Header
                os.write(DatabaseFormat.MAGIC);
                os.write(Ints.toByteArray(V1DatabaseFormat.FORMAT));

                final MessageDigest md;
                try {
                    md = MessageDigest.getInstance(
                            V1DatabaseFormat.MESSAGE_DIGEST_ALGORITHM);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }

                md.reset();

                // With digest calculation
                final MessageDigestOutputStreamWrapper mdos =
                        new MessageDigestOutputStreamWrapper(os, md);

                // Segments
                for (OutputStreamWritable writable : writables) {
                    writable.writeTo(mdos);
                }

                //writing checksum
                if (V1DatabaseFormat.DIGEST_SIZE_IN_BYTES !=
                    md.getDigestLength()) {
                    throw new IllegalArgumentException(
                            "Wrong digest size (" +
                            V1DatabaseFormat.DIGEST_SIZE_IN_BYTES +
                            " != " + md.getDigestLength() + ")");
                }

                os.write(mdos.digest());
            }
        };
    }
}
