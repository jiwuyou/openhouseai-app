package com.wuxianpi.openhouse.core;

/** Starts or stops service-manager itself. Business services are controlled only through HTTP. */
public interface ControlPlaneStarter {
    ControlPlaneResult startControlPlane();
    ControlPlaneResult stopControlPlane();
}
