package net.postchain.gtx.extensions.ai_inference

import mu.KLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.time.Duration

class AiInferenceContainerIT {
    companion object : KLogging()

    val container = GenericContainer("chromaway/ai-inference-extension-chromia-subnode")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("/log4j2-test.yml"),
                    "/opt/chromaway/postchain/log4j2.yml"
            )
            .withCopyFileToContainer(
                    MountableFile.forHostPath("target/test-classes/net/postchain/gtx/extensions/ai_inference/AiInferenceTesterKt.class"),
                    "/opt/chromaway/postchain/test-classpath/net/postchain/gtx/extensions/ai_inference/AiInferenceTesterKt.class"
            )
            .withCommand("-c", "java -ea -Duser.language=en -Duser.country=US -XX:+UnlockDiagnosticVMOptions -XX:AbortVMOnException=java.lang.OutOfMemoryError -classpath /opt/chromaway/postchain/libs/*:/opt/chromaway/postchain/classpath/*:/opt/chromaway/postchain/test-classpath net.postchain.gtx.extensions.ai_inference.AiInferenceTesterKt")
            .withLogConsumer(Slf4jLogConsumer(logger))
            .waitingFor(Wait.forLogMessage(".*Container test succeeded.*\\n", 1).withStartupTimeout(Duration.ofMinutes(10)));

    @AfterEach
    fun tearDown() {
        container.stop()
    }

    @Tag("docker")
    @Test
    fun `inference and validation in container`() {
        container.start()
    }
}
