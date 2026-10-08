package net.whago.annotrail;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MainTest {
 @Test void choicesRejectInvalidAndDuplicate() throws Exception {
  assertEquals(-1, Main.readChoices("{\"a\":-1}").get("a"));
  for(String s : new String[]{"{\"a\":0.5}","{\"a\":0,\"a\":1}","{\"a\":\"0\"}","{\"a\":0}true","{\"a\":null}"}) assertThrows(Exception.class,()->Main.readChoices(s));
 }
 @Test void repeatedOptionsAndUnknownCommandsFail() {
  assertThrows(IllegalArgumentException.class,()->Main.parse(new String[]{"analyze","--old","x","--old","y"}));
  assertEquals(2, Main.run(new String[]{"nonsense"}));
  assertEquals(2, Main.run(new String[]{"analyze"}));
 }
 @Test void versionAndHelp() { assertEquals(0,Main.run(new String[]{"--version"}));assertEquals(0,Main.run(new String[]{"--help"})); }
}
