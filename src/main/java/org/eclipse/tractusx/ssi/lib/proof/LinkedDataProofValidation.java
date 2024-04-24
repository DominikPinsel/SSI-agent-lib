/*
 * ******************************************************************************
 * Copyright (c) 2021,2024 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * *******************************************************************************
 */

package org.eclipse.tractusx.ssi.lib.proof;

import java.util.logging.Logger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.eclipse.tractusx.ssi.lib.did.resolver.DidResolver;
import org.eclipse.tractusx.ssi.lib.exception.did.DidParseException;
import org.eclipse.tractusx.ssi.lib.exception.json.InvalidJsonLdException;
import org.eclipse.tractusx.ssi.lib.exception.json.TransformJsonLdException;
import org.eclipse.tractusx.ssi.lib.exception.key.InvalidPublicKeyFormatException;
import org.eclipse.tractusx.ssi.lib.exception.proof.NoVerificationKeyFoundException;
import org.eclipse.tractusx.ssi.lib.exception.proof.SignatureParseException;
import org.eclipse.tractusx.ssi.lib.exception.proof.SignatureVerificationFailedException;
import org.eclipse.tractusx.ssi.lib.exception.proof.UnsupportedSignatureTypeException;
import org.eclipse.tractusx.ssi.lib.model.verifiable.Verifiable;
import org.eclipse.tractusx.ssi.lib.model.verifiable.Verifiable.VerifiableType;
import org.eclipse.tractusx.ssi.lib.model.verifiable.credential.VerifiableCredential;
import org.eclipse.tractusx.ssi.lib.model.verifiable.presentation.VerifiablePresentation;
import org.eclipse.tractusx.ssi.lib.proof.hash.HashedLinkedData;
import org.eclipse.tractusx.ssi.lib.proof.hash.LinkedDataHasher;
import org.eclipse.tractusx.ssi.lib.proof.transform.LinkedDataTransformer;
import org.eclipse.tractusx.ssi.lib.proof.transform.TransformedLinkedData;
import org.eclipse.tractusx.ssi.lib.proof.types.ed25519.Ed25519ProofVerifier;
import org.eclipse.tractusx.ssi.lib.proof.types.jws.JWSProofVerifier;
import org.eclipse.tractusx.ssi.lib.validation.JsonLdValidator;
import org.eclipse.tractusx.ssi.lib.validation.JsonLdValidatorImpl;

/** The type Linked data proof validation. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class LinkedDataProofValidation {
  static final Logger LOG = Logger.getLogger(LinkedDataProofValidation.class.getName());

  /**
   * New instance linked data proof validation.
   *
   * @param didResolver the did resolver
   * @return the linked data proof validation
   */
  public static LinkedDataProofValidation newInstance(DidResolver didResolver) {

    if (didResolver == null) {
      throw new NullPointerException("Document Resolver shouldn't be null");
    }

    return new LinkedDataProofValidation(
        new LinkedDataHasher(),
        new LinkedDataTransformer(),
        didResolver,
        new JsonLdValidatorImpl());
  }

  private final LinkedDataHasher hasher;
  private final LinkedDataTransformer transformer;
  private final DidResolver didResolver;
  private final JsonLdValidator jsonLdValidator;

  /**
   * To verify {@link VerifiableCredential} or {@link VerifiablePresentation}. In this method we are
   * depending on Verification Method to resolve the DID Document and fetching the required Public
   * Key
   *
   * @param verifiable is an object adhering to the 'Verifiable' contract (usually a VC or VP)
   * @return a boolean indicating if the object is valid
   * @throws UnsupportedSignatureTypeException is thrown when the signature algorithm is unknown
   * @throws SignatureParseException is thrown if the signature cannot be parsed
   * @throws DidParseException is thrown if the DID cannot be parsed
   * @throws InvalidPublicKeyFormatException is thrown if the public key is in an unknown format
   * @throws SignatureVerificationFailedException is thrown if the signature cannot be verified
   * @throws NoVerificationKeyFoundException is thrown if the required public verification key
   *     cannot be found
   * @throws TransformJsonLdException if the json-ld cannot be canonicalized
   */
  public boolean verify(Verifiable verifiable)
      throws UnsupportedSignatureTypeException, SignatureParseException, DidParseException,
          InvalidPublicKeyFormatException, SignatureVerificationFailedException,
          NoVerificationKeyFoundException, TransformJsonLdException {

    var type = verifiable.getProof().getType();
    IVerifier verifier = null;

    if (type != null && !type.isBlank()) {
      if (type.equals(SignatureType.ED25519.toString())) {
        verifier = new Ed25519ProofVerifier(this.didResolver);
      } else if (type.equals(SignatureType.JWS.toString())) {
        verifier = new JWSProofVerifier(this.didResolver);
      } else {
        throw new UnsupportedSignatureTypeException(
            String.format("%s is not supported type", type));
      }
    } else {
      throw new UnsupportedSignatureTypeException("Proof type can't be empty");
    }

    // We need to make a deep copy to keep the original Verifiable as it is for Verification step
    var verifiableWithoutProofSignature = verifiable.deepClone().removeProofSignature();

    final TransformedLinkedData transformedData =
        transformer.transform(verifiableWithoutProofSignature);
    final HashedLinkedData hashedData = hasher.hash(transformedData);

    try {
      jsonLdValidator.validate(verifiableWithoutProofSignature);
      return verifier.verify(hashedData, verifiable) && validateVerificationMethodOfVC(verifiable);
    } catch (InvalidJsonLdException e) {
      LOG.severe("Could not valiate " + verifiable.getId());
      LOG.throwing(this.getClass().getName(), "verify", e);
      return false;
    }
  }

  /**
   * This method is to validate the Verification Method of VC
   *
   * @param verifiable the verifiable
   * @return validation result
   */
  @SneakyThrows
  private Boolean validateVerificationMethodOfVC(Verifiable verifiable) {
    // Verifiable Presentation doesn't have an Issuer
    if (verifiable.getType() == VerifiableType.VP) {
      return true;
    }
    final VerifiableCredential vc = new VerifiableCredential(verifiable);
    final String issuer = vc.getIssuer().toString();
    final String verificationMethod = getVerificationMethod(verifiable);
    final String[] splitVerificationMethod = verificationMethod.split("#");
    return splitVerificationMethod[0].equals(issuer);
  }

  /**
   * This method is to get the Verification Method of VC
   *
   * @param verifiable
   * @return
   * @throws UnsupportedSignatureTypeException
   */
  private String getVerificationMethod(Verifiable verifiable)
      throws UnsupportedSignatureTypeException {
    final String VERIFICATION_METHOD = "verificationMethod";
    try {
      return (String) verifiable.getProof().get(VERIFICATION_METHOD);
    } catch (Exception e) {
      throw new UnsupportedSignatureTypeException("Signature type is not supported");
    }
  }
}
