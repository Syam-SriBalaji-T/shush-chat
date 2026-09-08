package site.syamdev.shush.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import site.syamdev.shush.support.AbstractIT;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the actual test client in a real browser, through the journey a person takes: land,
 * pick interests, get matched with a stranger, exchange a message, ask to keep them.
 *
 * <p>Two browsers, because a chat with one participant proves nothing. Everything here goes
 * through the same HTTP and WebSocket protocol a visitor would use -- no test hooks, no injected
 * state, and nothing clicked by hand.
 *
 * <p>Selenium in a container rather than Playwright so the whole journey runs inside
 * {@code ./mvnw verify} with no Node toolchain to install first.
 */
class TestClientJourneyIT extends AbstractIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * Two containers, not two sessions in one: a standalone browser container serves a single
     * session, and this journey needs two people talking to each other.
     */
    private static BrowserWebDriverContainer<?> aliceBrowser;
    private static BrowserWebDriverContainer<?> bobBrowser;

    @LocalServerPort
    private int port;

    /**
     * The host port has to be exposed <em>before</em> the browser containers start, or
     * {@code host.testcontainers.internal} does not resolve inside them. The port is only known
     * once Spring has started, so the containers cannot be created in a static initialiser.
     */
    @BeforeEach
    void exposeTheApplicationToTheBrowsers() {
        Testcontainers.exposeHostPorts(port);
        if (aliceBrowser == null) {
            aliceBrowser = startBrowser();
            bobBrowser = startBrowser();
        }
    }

    @AfterAll
    static void stopBrowsers() {
        if (aliceBrowser != null) {
            aliceBrowser.stop();
        }
        if (bobBrowser != null) {
            bobBrowser.stop();
        }
    }

    private static BrowserWebDriverContainer<?> startBrowser() {
        BrowserWebDriverContainer<?> container = new BrowserWebDriverContainer<>()
                .withCapabilities(new ChromeOptions()
                        // /dev/shm is small in a container and Chrome crashes without this.
                        .addArguments("--no-sandbox", "--disable-dev-shm-usage"));
        container.start();
        return container;
    }

    @Test
    void aVisitorCanLandPickInterestsGetMatchedTalkAndAskToKeepTheOtherPerson() {
        // The application runs on the host; the browsers run in containers and reach it here.
        String appUrl = "http://host.testcontainers.internal:" + port + "/";

        WebDriver alice = openBrowser(aliceBrowser);
        WebDriver bob = openBrowser(bobBrowser);
        try {
            // Land. One line, one button.
            alice.get(appUrl);
            bob.get(appUrl);
            click(alice, By.id("startChatting"));
            click(bob, By.id("startChatting"));

            // Both get a generated name without ever typing one.
            waitFor(alice).until(driver -> !driver.findElement(By.id("displayName")).getText().isBlank());
            assertThat(alice.findElement(By.id("displayName")).getText())
                    .matches("[A-Z][a-z]+ [A-Z][a-z]+( \\d+)?");

            // Pick the same interest on both sides, so this is a real interest match.
            waitFor(alice).until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("#interestTiles .tile")));
            waitFor(bob).until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("#interestTiles .tile")));
            String sharedInterest = firstInterestId(alice);
            selectInterest(alice, sharedInterest);
            selectInterest(bob, sharedInterest);

            // Find someone. Both are waiting, so they find each other.
            click(alice, By.id("findSomeone"));
            click(bob, By.id("findSomeone"));

            waitFor(alice).until(ExpectedConditions.visibilityOfElementLocated(By.id("chat")));
            waitFor(bob).until(ExpectedConditions.visibilityOfElementLocated(By.id("chat")));

            // The header says why they were put together. Compared case-insensitively because
            // getText() returns rendered text and the heading is uppercased by CSS.
            assertThat(alice.findElement(By.id("chatHeading")).getText())
                    .as("an interest match says so rather than pretending to be one")
                    .containsIgnoringCase("you both like")
                    .doesNotContainIgnoringCase("random");

            // Talk, in both directions.
            type(alice, By.id("composer"), "hello from alice");
            click(alice, By.id("send"));
            waitFor(bob).until(driver -> driver.findElement(By.id("messages")).getText()
                    .contains("hello from alice"));

            type(bob, By.id("composer"), "hello from bob");
            click(bob, By.id("send"));
            waitFor(alice).until(driver -> driver.findElement(By.id("messages")).getText()
                    .contains("hello from bob"));

            // Ask to keep them, and see it accepted.
            click(alice, By.id("addFriend"));
            // visibilityOf, not presenceOf. Presence was the weaker assertion and it hid a real
            // bug: the request list lived in a panel that was only ever revealed by a match, so
            // an incoming request was delivered, added to the DOM, and invisible to the person
            // it was for. The test passed the whole time. An element the recipient cannot see
            // is not a delivered request.
            waitFor(bob).until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#requests li button")));
            bob.findElements(By.cssSelector("#requests li button")).getFirst().click();

            waitFor(alice).until(driver -> driver.findElement(By.id("messages")).getText()
                    .contains("in your friends list"));
            waitFor(bob).until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#friends li")));

            assertThat(bob.findElements(By.cssSelector("#friends li"))).hasSize(1);

            // The friends list is the only route back to somebody you have kept: matching
            // deliberately refuses to pair you with an existing friend, so if this row is not
            // clickable there is no way to ever talk to them again.
            WebElement friendRow = bob.findElement(By.cssSelector("#friends li .person"));
            friendRow.click();
            waitFor(bob).until(driver -> driver.findElement(By.id("messages")).getText()
                    .contains("hello from alice"));
        } finally {
            alice.quit();
            bob.quit();
        }
    }

    @Test
    void darkModeIsTheDefaultAndTheChoiceIsRemembered() {
        String appUrl = "http://host.testcontainers.internal:" + port + "/";

        WebDriver browser = openBrowser(aliceBrowser);
        try {
            browser.get(appUrl);
            assertThat(browser.findElement(By.tagName("html")).getDomAttribute("data-theme"))
                    .as("dark is the default, not a preference to be discovered")
                    .isEqualTo("dark");

            click(browser, By.id("themeToggle"));
            assertThat(browser.findElement(By.tagName("html")).getDomAttribute("data-theme"))
                    .isEqualTo("light");

            browser.navigate().refresh();
            waitFor(browser).until(driver ->
                    "light".equals(driver.findElement(By.tagName("html")).getDomAttribute("data-theme")));
        } finally {
            browser.quit();
        }
    }

    private static WebDriver openBrowser(BrowserWebDriverContainer<?> container) {
        return new RemoteWebDriver(container.getSeleniumAddress(), new ChromeOptions());
    }

    private static WebDriverWait waitFor(WebDriver driver) {
        return new WebDriverWait(driver, PATIENCE);
    }

    private static void click(WebDriver driver, By locator) {
        waitFor(driver).until(ExpectedConditions.elementToBeClickable(locator)).click();
    }

    private static void type(WebDriver driver, By locator, String text) {
        WebElement field = waitFor(driver).until(ExpectedConditions.visibilityOfElementLocated(locator));
        field.clear();
        field.sendKeys(text);
    }

    private static String firstInterestId(WebDriver driver) {
        List<WebElement> tiles = driver.findElements(By.cssSelector("#interestTiles .tile"));
        assertThat(tiles).isNotEmpty();
        return tiles.getFirst().getDomAttribute("data-interest-id");
    }

    private static void selectInterest(WebDriver driver, String interestId) {
        By locator = By.cssSelector("#interestTiles .tile[data-interest-id='" + interestId + "']");
        WebElement tile = waitFor(driver).until(ExpectedConditions.elementToBeClickable(locator));
        if (!"true".equals(tile.getDomAttribute("aria-pressed"))) {
            tile.click();
        }
    }
}
