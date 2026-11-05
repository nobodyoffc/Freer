package com.fc.freer.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.data.feipData.Image;
import com.fc.fc_ajdk.data.feipData.Token;

import org.junit.Test;

/** FEIP20 Cover rule 3: a token's logo is only its deployer's own, undeleted Image. */
public class TokenLogoManagerTest {
    private static final String IMAGE_ID = "ab".repeat(32);

    private static Token token(String cover, String deployer) {
        Token t = new Token();
        t.setId("cd".repeat(32));
        t.setCover(cover);
        t.setDeployer(deployer);
        return t;
    }

    private static Image image(String publisher, Boolean deleted) {
        Image i = new Image();
        i.setId(IMAGE_ID);
        i.setPublisher(publisher);
        i.setDeleted(deleted);
        return i;
    }

    @Test
    public void theDeployersImageIsTheLogo() {
        assertTrue(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), image("FA", null)));
        assertTrue(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), image("FA", false)));
    }

    @Test
    public void anyoneElsesImageIsNot() {
        assertFalse(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), image("FB", null)));
        assertFalse(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), image(null, null)));
        assertFalse(TokenLogoManager.showsLogo(token(IMAGE_ID, null), image("FA", null)));
    }

    @Test
    public void nothingIsShownForADeletedImageOrAMissingCover() {
        assertFalse(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), image("FA", true)));
        assertFalse(TokenLogoManager.showsLogo(token(IMAGE_ID, "FA"), null));
        assertFalse(TokenLogoManager.showsLogo(token(null, "FA"), image("FA", null)));
        assertFalse(TokenLogoManager.hasCover(token("https://example.org/logo.png", "FA")));
    }
}
