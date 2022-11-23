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
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant;
import org.gradle.api.internal.attributes.ImmutableAttributes;

public class TransformedVariant implements HasAttributes {
    private final ResolvedVariant root;
    private final VariantDefinition chain;

    public TransformedVariant(ResolvedVariant root, VariantDefinition chain) {
        this.root = root;
        this.chain = chain;
    }

    @Override
    public String toString() {
        return chain.toString();
    }

    public VariantDefinition getVariantChain() {
        return chain;
    }

    public Transformation getTransformation() {
        return chain.getTransformation();
    }

    @Override
    public ImmutableAttributes getAttributes() {
        return chain.getTargetAttributes();
    }

    /**
     * Get the root producer variant which the transform chain is applied to.
     */
    public ResolvedVariant getRoot() {
        return root;
    }
}
