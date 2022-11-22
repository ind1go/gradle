/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;

import javax.annotation.Nullable;

/**
 * A component of a chain of transformed variants, each of which apply some transformation to the previous variant.
 */
public class TransformedVariant implements VariantDefinition, HasAttributes {
    private final Integer root;
    private final TransformedVariant previous;
    private final ImmutableAttributes attributes;
    private final Transformation transformation;
    private final TransformationStep transformationStep;
    private final int depth;

    /**
     * Create a root transformed variant, which is based off of a resolved, "producer", variant.
     * We store the index of the root instead of the variant itself to allow this result to be cached.
     */
    public TransformedVariant(
        int root,
        ImmutableAttributes attributes,
        TransformationStep transformationStep
    ) {
        this.root = root;
        this.attributes = attributes;
        this.transformationStep = transformationStep;

        this.transformation = transformationStep;
        this.previous = null;
        this.depth = 1;
    }

    /**
     * Create a chained transformed variant, which is based off another transformed, "consumer", variant.
     */
    public TransformedVariant(
        TransformedVariant previous,
        ImmutableAttributes attributes,
        TransformationStep transformationStep
    ) {
        this.previous = previous;
        this.attributes = attributes;
        this.transformationStep = transformationStep;

        this.transformation = new TransformationChain(previous.transformation, transformationStep);
        this.root = null;
        this.depth = previous.getDepth() + 1;
    }

    @Override
    public String toString() {
        if (previous != null) {
            return previous + " <- (" + depth + ") " + transformationStep;
        } else {
            return "(" + depth + ") " + transformationStep;
        }
    }

    @Override
    public ImmutableAttributes getTargetAttributes() {
        return attributes;
    }

    @Override
    public Transformation getTransformation() {
        return transformation;
    }

    @Override
    public TransformationStep getTransformationStep() {
        return transformationStep;
    }

    @Nullable
    @Override
    public VariantDefinition getSourceVariant() {
        return previous;
    }

    @Override
    public ImmutableAttributes getAttributes() {
        return attributes;
    }

    /**
     * Get the index of the root producer variant within the list of source variants.
     */
    public Integer getRootIndex() {
        return root == null ? previous.getRootIndex() : root;
    }

    @Nullable
    public TransformedVariant getPrevious() {
        return previous;
    }

    public int getDepth() {
        return depth;
    }
}
