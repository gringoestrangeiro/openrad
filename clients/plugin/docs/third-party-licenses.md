# Licenças e dependências

## Código distribuído no plugin

OpenRad Plugin e a portabilidade dos componentes OpenRad seguem a licença MIT em `LICENSE`, também incluída no JAR em `META-INF/LICENSE`. O aviso original de copyright deve ser preservado. Referência: https://github.com/gringoestrangeiro/openrad, fonte local 2.0.2.

A implementação de runtime usa somente APIs do JDK e APIs Paper fornecidas pelo servidor. Não há dependência de runtime a embutir, código Kotlin, JNI, binário Rust, driver, executável ou download auxiliar dentro do plugin. `gradlew dependencies --configuration runtimeClasspath` confirma a configuração sem dependências.

## Dependências fornecidas ou de desenvolvimento

| Componente | Uso | Licença / distribuição |
| --- | --- | --- |
| Paper API 1.21.1-R0.1-SNAPSHOT | compileOnly; fornecida pelo host | Não embutida no JAR. Consulte https://github.com/PaperMC/Paper para os avisos aplicáveis. |
| JUnit Jupiter 5.11.4 e plataforma | Testes locais | EPL-2.0; não distribuído no plugin. https://github.com/junit-team/junit5 |
| Gradle Wrapper / Gradle 9.1.0 | Build | Apache-2.0; distribuição oficial obtida pelo wrapper com SHA256 fixado. https://github.com/gradle/gradle |
| Google Java Format 1.37.0 | Formatação durante desenvolvimento | Apache-2.0; ferramenta externa de desenvolvimento, não embutida. https://github.com/google/google-java-format |
| OpenRad Rust 2.0.2 | Referência e teste de interoperabilidade | MIT; fonte externa mantida intacta; ferramentas de teste separadas não entram no JAR. |
| Temurin JDK 21 | Compilação e ambiente Paper de teste | Somente ambiente de desenvolvimento; não redistribuído nesta entrega. https://adoptium.net/ |

O inventário verifica o conteúdo efetivamente empacotado e a árvore de dependências. Não foi emitida uma certificação externa de ausência de vulnerabilidades. As limitações criptográficas do protocolo legado estão em `threat-model.md`.
