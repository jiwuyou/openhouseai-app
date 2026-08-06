package com.wuxianpi.openhouse.core.service;

import com.wuxianpi.openhouse.core.RuntimeConnection;
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.*;

public class ServiceManagerClientTest {
    @Test public void parsesServiceListAndNestedStatusFields() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new HttpResponseSpec(200,
            "{\"services\":[{\"spec\":{\"id\":\"pi-agent\",\"name\":\"Pi\",\"provider\":\"termux\",\"tags\":[\"ai\"]},"
                + "\"status\":{\"state\":\"running\",\"pid\":42,\"url\":\"http://127.0.0.1:30141/\"}}]}"));
        ServiceManagerResult result = client(transport).listServices();
        assertTrue(result.success);
        assertEquals(1, result.services.size());
        ServiceManagerService service = result.services.get(0);
        assertEquals("pi-agent", service.id);
        assertEquals("running", service.state);
        assertEquals(Integer.valueOf(42), service.pid);
        assertEquals("http://127.0.0.1:30141/", service.url);
        assertEquals("/api/v1/services", transport.requests.get(0).path);
    }

    @Test public void parsesBulkServiceStatuses() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new HttpResponseSpec(200,
            "[{\"service\":{\"id\":\"pi-web\",\"spec\":{\"name\":\"web\",\"provider\":\"process\","
                + "\"tags\":[\"group:ai\"]}},\"status\":{\"service_id\":\"pi-web\","
                + "\"state\":\"starting\",\"provider\":\"process\",\"pid\":52}}]"));

        ServiceManagerResult result = client(transport).listServiceStatuses();

        assertTrue(result.success);
        assertEquals(1, result.services.size());
        assertEquals("pi-web", result.services.get(0).id);
        assertEquals("web", result.services.get(0).name);
        assertEquals("starting", result.services.get(0).state);
        assertEquals("group:ai", result.services.get(0).tags.get(0));
        assertEquals("/api/v1/services/statuses", transport.requests.get(0).path);
    }

    @Test public void nodeLifecycleActionsUseAuthenticatedHttpPostOnly() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new HttpResponseSpec(204, ""));
        transport.responses.add(new HttpResponseSpec(204, ""));
        transport.responses.add(new HttpResponseSpec(204, ""));
        transport.responses.add(new HttpResponseSpec(204, ""));
        ServiceManagerClient client = client(transport);

        assertTrue(client.runAction("pi-agent", ServiceAction.START).success);
        assertTrue(client.runAction("pi-agent", ServiceAction.STOP).success);
        assertTrue(client.runAction("pi-agent", ServiceAction.RESTART).success);
        assertTrue(client.runAction("pi-agent", ServiceAction.REPAIR).success);

        assertEquals(4, transport.requests.size());
        assertLifecycleRequest(transport.requests.get(0), "/api/v1/services/pi-agent/start");
        assertLifecycleRequest(transport.requests.get(1), "/api/v1/services/pi-agent/stop");
        assertLifecycleRequest(transport.requests.get(2), "/api/v1/services/pi-agent/restart");
        assertLifecycleRequest(transport.requests.get(3), "/api/v1/services/pi-agent/repair");
    }

    @Test public void groupLifecycleActionsUseAuthenticatedHttpPost() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new HttpResponseSpec(200, "{}"));
        assertTrue(client(transport).runGroupAction("ai", ServiceAction.RESTART).success);
        assertEquals(1, transport.requests.size());
        assertLifecycleRequest(transport.requests.get(0), "/api/v1/groups/ai/restart");
    }

    @Test public void controlPlaneContractCannotStartBusinessServices() {
        List<String> methodNames = new ArrayList<>();
        for (java.lang.reflect.Method method : com.wuxianpi.openhouse.core.ControlPlaneStarter.class.getDeclaredMethods()) {
            methodNames.add(method.getName());
        }
        assertEquals(2, methodNames.size());
        assertTrue(methodNames.contains("startControlPlane"));
        assertTrue(methodNames.contains("stopControlPlane"));
        for (String name : methodNames) {
            assertFalse(name.toLowerCase().contains("runtime"));
            assertFalse(name.toLowerCase().contains("node"));
            assertFalse(name.toLowerCase().contains("service"));
        }
    }

    @Test public void rejectsInvalidServiceIdsBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        ServiceManagerResult result = client(transport).runAction("../pi-agent", ServiceAction.START);
        assertFalse(result.success);
        assertTrue(transport.requests.isEmpty());
    }

    @Test public void registryApiParsesRecordsAndStateRevision() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(new HttpResponseSpec(200,
            "[{\"id\":\"demo\",\"path\":\"components.d/demo.json\",\"manifest\":" + manifest("demo") + "}]"));
        transport.responses.add(new HttpResponseSpec(200,
            "{\"version\":3,\"generatedAt\":\"2026-07-21T00:00:00Z\",\"status\":\"ok\"}"));
        RegistryRemoteResult result = client(transport).loadRegistry();
        assertTrue(result.success);
        assertEquals("3:2026-07-21T00:00:00Z", result.revision);
        assertEquals(1, result.manifests.size());
        assertEquals("demo", result.manifests.get(0).id);
        assertEquals("/api/v1/registry/components", transport.requests.get(0).path);
        assertEquals("/api/v1/registry/state", transport.requests.get(1).path);
    }

    @Test public void parsesServiceManagerReferences() {
        ServiceManagerTarget service = ServiceManagerClient.parseServiceManagerRef("service-manager://services/pi-agent");
        ServiceManagerTarget action = ServiceManagerClient.parseServiceManagerRef("service-manager://actions/pi-agent.restart");
        assertTrue(service.valid);
        assertEquals("pi-agent", service.serviceId);
        assertTrue(action.valid);
        assertSame(ServiceAction.RESTART, action.action);
    }

    private static ServiceManagerClient client(FakeTransport transport) {
        return new ServiceManagerClient(new RuntimeConnection("http://127.0.0.1:20087", "token", ""), transport);
    }

    private static void assertLifecycleRequest(HttpRequestSpec request, String path) {
        assertEquals(path, request.path);
        assertEquals("POST", request.method);
        assertTrue(request.authenticated);
    }

    private static String manifest(String id) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"title\":\"Demo\","
            + "\"shellMenu\":{},\"smallphoneApp\":{},\"serviceManager\":{},\"ai\":{}}";
    }

    private static final class FakeTransport implements HttpTransport {
        final Queue<HttpResponseSpec> responses = new ArrayDeque<>();
        final List<HttpRequestSpec> requests = new ArrayList<>();
        @Override public HttpResponseSpec execute(RuntimeConnection connection, HttpRequestSpec request) throws IOException {
            requests.add(request);
            if (responses.isEmpty()) throw new IOException("no response queued");
            return responses.remove();
        }
    }
}
