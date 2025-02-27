/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.security.mechanism.digest;

import static org.wildfly.security.mechanism._private.ElytronMessages.log;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.function.Supplier;

import org.wildfly.common.bytes.ByteStringBuilder;
import org.wildfly.security.mechanism._private.ElytronMessages;
import org.wildfly.security.mechanism.AuthenticationMechanismException;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.TwoWayPassword;
import org.wildfly.security.password.spec.ClearPasswordSpec;

/**
 * Common utility functions used by Digest authentication mechanisms.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 * @author <a href="mailto:pskopek@redhat.com">Peter Skopek</a>.
 */
public class DigestUtil {

    private static final int MAX_PARSED_RESPONSE_SIZE = 13;

    /**
     * Client side method to parse challenge sent by server.
     *
     * @param challenge the byte array representing the authentication challenge to be parsed.
     * @param charset the charset to decide which whitespace separator is used.
     * @param multiRealm {@code true} if there are multiple realms in the challenge, {@code false} otherwise
     * @param log the logger to use.
     * @return A new HashMap representing response for the parsed challenge
     * @throws AuthenticationMechanismException if there is an error parsing the challenge
     */
    public static HashMap<String, byte[]> parseResponse(byte [] challenge, Charset charset, boolean multiRealm, ElytronMessages log) throws AuthenticationMechanismException {

        HashMap<String, byte[]> response = new HashMap<String, byte[]> (MAX_PARSED_RESPONSE_SIZE);
        int i = skipWhiteSpace(challenge, 0);

        StringBuilder key = new StringBuilder(10);
        ByteStringBuilder value = new ByteStringBuilder();

        int realmNumber = multiRealm ? 0 : -1;

        boolean insideKey = true;
        boolean insideQuotedValue = false;
        boolean expectSeparator = false;

        byte b;
        while (i < challenge.length) {
            b = challenge[i];
            // parsing keyword
            if (insideKey) {
                if (b == ',') {
                    throw log.mechKeywordNotFollowedByEqual(key.toString());
                }
                else if (b == '=') {
                    if (key.length() == 0) {
                        throw log.mechKeywordCannotBeEmpty();
                    }
                    insideKey = false;
                    i = skipWhiteSpace(challenge, i + 1);

                    if (i < challenge.length) {
                        if (challenge[i] == '"') {
                            insideQuotedValue = true;
                            ++i; // Skip quote
                        }
                    }
                    else {
                        throw log.mechNoValueFoundForKeyword(key.toString());
                    }
                }
                else if (isWhiteSpace(b)) {
                    i = skipWhiteSpace(challenge, i + 1);

                    if (key.length() > 0) {
                        if (i < challenge.length) {
                            if (challenge[i] != '=') {
                                throw log.mechKeywordNotFollowedByEqual(key.toString());
                            }
                        } else {
                            throw log.mechKeywordNotFollowedByEqual(key.toString());
                        }
                    }
                }
                else {
                    key.append((char)(b & 0xff));
                    i++;
                }
            }
            // parsing quoted value
            else if (insideQuotedValue) {
                if (b == '\\') {
                    i++; // skip the escape char
                    if (i < challenge.length) {
                        value.append(challenge[i]);
                        i++;
                    }
                    else {
                        throw log.mechUnmatchedQuoteFoundForValue(value.toString());
                    }
                }
                else if (b == '"') {
                    // closing quote
                    i++;
                    insideQuotedValue = false;
                    expectSeparator = true;
                }
                else {
                    value.append(b);
                    i++;
                }
            }
            // terminated value
            else if (isWhiteSpace(b) || b == ',') {
                realmNumber = addToParsedChallenge(response, key, value, realmNumber);
                key = new StringBuilder();
                value = new ByteStringBuilder();
                i = skipWhiteSpace(challenge, i);
                if (i < challenge.length && challenge[i] == ',') {
                    expectSeparator = false;
                    insideKey = true;
                    i++;
                }
            }
            // expect separator
            else if (expectSeparator) {
                String val = new String(value.toArray(), charset);
                throw log.mechExpectingCommaOrLinearWhitespaceAfterQuoted(val);
            }
            else {
                value.append(b);
                i++;
            }
        }

        if (insideQuotedValue) {
            throw log.mechUnmatchedQuoteFoundForValue(value.toString());
        }

        if (key.length() > 0) {
            addToParsedChallenge(response, key, value, realmNumber);
        }

        return response;
    }

    /**
     * Adds a key-value pair to a HashMap representing a parsed challenge.
     *
     * @param response the HashMap to add the key-value pair to.
     * @param keyBuilder the StringBuilder containing the key.
     * @param valueBuilder the ByteStringBuilder containing the value.
     * @param realmNumber the current number of realms in the parsed challenge.
     * @return the updated number of realms in the parsed challenge.
     */
    private static int addToParsedChallenge(HashMap<String, byte[]> response, StringBuilder keyBuilder, ByteStringBuilder valueBuilder, int realmNumber) {
        String k = keyBuilder.toString();
        byte[] v = valueBuilder.toArray();
        if (realmNumber >= 0 && "realm".equals(k)) {
            response.put(k + ":" + String.valueOf(realmNumber), v);
            realmNumber++;
        }
        else {
            response.put(k, v);
        }
        return realmNumber;
    }

    /**
     * Finds the next non-whitespace character in a byte array.
     *
     * @param buffer the byte array to search in.
     * @param startPoint the starting point in the buffer to begin the search.
     * @return the index of the next non-whitespace character.
     */
    private static int skipWhiteSpace(byte[] buffer, int startPoint) {
        int i = startPoint;
        while (i < buffer.length && isWhiteSpace(buffer[i])) {
            i++;
        }
        return i;
    }

    /**
     * Checks if a given byte is a whitespace character.
     *
     * @param b the byte to check.
     * @return {@code true} if the byte is a whitespace character, {@code false} otherwise.
     */
    private static boolean isWhiteSpace(byte b) {
        if (b == 13)   // CR
            return true;
        else if (b == 10) // LF
            return true;
        else if (b == 9) // TAB
            return true;
        else if (b == 32) // SPACE
            return true;
        else
            return false;
    }

    /**
     * Digests the concatenated username, realm and password.
     *
     * @param messageDigest the message digest algorithm to use when computing the digest.
     * @param username the username to use when concatenating.
     * @param realm the realm to use when concatenating.
     * @param password the password in the form of a char array to use when concatenating.
     * @return byte array of the digested password.
     */
    public static byte[] userRealmPasswordDigest(MessageDigest messageDigest, String username, String realm, char[] password) {
        CharsetEncoder latin1Encoder = StandardCharsets.ISO_8859_1.newEncoder();
        latin1Encoder.reset();
        boolean bothLatin1 = latin1Encoder.canEncode(username);
        latin1Encoder.reset();
        if (bothLatin1) {
            for (char c: password) {
                bothLatin1 = bothLatin1 && latin1Encoder.canEncode(c);
            }
        }

        Charset chosenCharset = StandardCharsets.UTF_8; // bothLatin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;

        ByteStringBuilder urp = new ByteStringBuilder(); // username:realm:password
        urp.append(username.getBytes(chosenCharset));
        urp.append(':');
        if (realm != null) {
            urp.append(realm.getBytes((chosenCharset)));
        } else {
            urp.append("");
        }
        urp.append(':');
        urp.append(new String(password).getBytes((chosenCharset)));

        return messageDigest.digest(urp.toArray());
    }

    /**
     * Get array of password chars from TwoWayPassword.
     *
     * @param password the TwoWayPassword that needs to be processed.
     * @param providers the supplier for the providers to be used for processing.
     * @param log the logger to use.
     * @throws AuthenticationMechanismException if there is an error retrieving the encoded password.
     * @return encoded password in the form of a char array.
     */
    public static char[] getTwoWayPasswordChars(TwoWayPassword password, Supplier<Provider[]> providers, ElytronMessages log) throws AuthenticationMechanismException {
        if (password == null) {
            throw log.mechNoPasswordGiven();
        }
        try {
            PasswordFactory pf = PasswordFactory.getInstance(password.getAlgorithm(), providers);
            return pf.getKeySpec(pf.translate(password), ClearPasswordSpec.class).getEncodedPassword();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException e) {
            throw log.mechCannotGetTwoWayPasswordChars(e);
        }
    }
}
