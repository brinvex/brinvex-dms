package com.brinvex.dms.api;

import com.brinvex.dms.internal.FilesystemDmsFactoryImpl;

import java.nio.file.Path;

public interface DmsFactory {

    Dms getDms(String workspace);

    static DmsFactory newFilesystemDmsFactory(Path basePath) {
        return new FilesystemDmsFactoryImpl(basePath);
    }
}
