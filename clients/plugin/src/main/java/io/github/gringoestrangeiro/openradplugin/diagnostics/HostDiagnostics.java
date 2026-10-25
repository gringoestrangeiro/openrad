package io.github.gringoestrangeiro.openradplugin.diagnostics;

import io.github.gringoestrangeiro.openradplugin.config.BridgeSettings;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HostDiagnostics {
    private HostDiagnostics() {}

    public static List<String> run(BridgeSettings settings) {
        List<String> results = new ArrayList<>();
        results.add(
                tcp(
                        "Backend TCP local",
                        settings.backendHost(),
                        settings.backendPort(),
                        settings.diagnosticTimeoutMillis()));
        if (!settings.tcpProbeHost().isBlank()) {
            results.add(
                    tcp(
                            "TCP egress (alvo configurado)",
                            settings.tcpProbeHost(),
                            settings.tcpProbePort(),
                            settings.diagnosticTimeoutMillis()));
        } else {
            results.add("TCP egress externo: não testado (nenhum destino configurado).");
        }
        if (!settings.udpProbeHost().isBlank()) {
            results.add(udp(settings.udpProbeHost(), settings.udpProbePort()));
        } else {
            results.add("UDP egress externo: não testado (nenhum destino configurado).");
        }
        results.add(
                "Estes probes não validam autenticação Radmin. Consulte /orb status para o estado"
                    + " da sessão.");
        return List.copyOf(results);
    }

    private static String tcp(String name, String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return name + ": TCP conectado.";
        } catch (Exception ex) {
            return name + ": falhou (" + ex.getClass().getSimpleName() + ").";
        }
    }

    private static String udp(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] payload = "OpenRad Plugin-probe".getBytes(StandardCharsets.US_ASCII);
            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(payload, payload.length, address, port));
            return "UDP egress (alvo configurado): pacote enviado; entrega e resposta não"
                       + " verificadas.";
        } catch (Exception ex) {
            return "UDP egress: falhou (" + ex.getClass().getSimpleName() + ").";
        }
    }
}
