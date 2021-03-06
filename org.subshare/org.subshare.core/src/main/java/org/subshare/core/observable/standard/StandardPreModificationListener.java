/*
 * Copyright 2003-2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.subshare.core.observable.standard;

import org.subshare.core.observable.ModificationListener;
import org.subshare.core.observable.ModificationVetoedException;

/**
 * A listener for the <code>StandardModificationHandler</code> that is called
 * when a collection is about to be modified.
 *
 * @since Commons Events 1.0
 * @version $Revision: 155443 $ $Date: 2005-02-26 20:19:51 +0700 (Sa, 26 Feb 2005) $
 *
 * @author Stephen Colebourne
 */
public interface StandardPreModificationListener extends ModificationListener {

    /**
     * A collection modification is occurring.
     * <p>
     * To veto the change, throw <code>ModificationVetoedException</code>.
     * <p>
     * This method should be processed quickly, as with all event handling.
     * It should also avoid modifying the event source (the collection).
     *
     * @param event  the event detail
     * @throws ModificationVetoedException to veto
     */
    public void modificationOccurring(StandardPreModificationEvent event);

}
