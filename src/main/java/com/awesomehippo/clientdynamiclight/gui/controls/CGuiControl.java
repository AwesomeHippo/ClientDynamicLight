/*
 * Copyright (c) 2025-2026 AwesomeHippo and contributors
 * All modifications must stay under MPL 2.0 and credit original authors.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.awesomehippo.clientdynamiclight.gui.controls;

public interface CGuiControl {

    public int id();
    
    public String tooltip();
    
    public void load();

    public void save();

}
