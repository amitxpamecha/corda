package com.template.ledger.flow;

import com.google.common.collect.ImmutableList;
import com.template.ledger.common.constants.GoldBlockConstants;
import net.corda.core.contracts.*;
import net.corda.core.crypto.SecureHash;
import net.corda.core.crypto.TransactionSignature;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.identity.PartyAndCertificate;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria.VaultQueryCriteria;
import net.corda.core.transactions.LedgerTransaction;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import net.corda.core.utilities.UntrustworthyData;
import net.corda.finance.contracts.Commodity;
import net.corda.finance.contracts.asset.CommodityContract;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Duration;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import static net.corda.core.crypto.CryptoUtils.generateKeyPair;

@InitiatingFlow
@StartableByRPC
public class SellerFlow extends FlowLogic<SignedTransaction> implements GoldBlockConstants {

    /*Write progress tracker*/
    private static final Step IDENTIFY_OTHER_NODES = new Step("Identifying other nodes on the network.");
    private static final Step SENDING_AND_RECEIVING_DATA = new Step("Sending data between parties.");
    private static final Step EXTRACTING_VAULT_STATES = new Step("Extracting states from the vault.");
    private static final Step OTHER_TX_COMPONENTS = new Step("Gathering a transaction's other components.");
    private static final Step TX_BUILDING = new Step("Building a transaction.");
    private static final Step TX_VERIFICATION = new Step("Verifying a transaction.");
    private static final Step TX_SIGNING = new Step("Signing a transaction.");
    private static final Step SIGS_GATHERING = new Step("Gathering a transaction's signatures.") {
        // Wiring up a child progress tracker allows us to see the
        // subflow's progress steps in our flow's progress tracker.
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.tracker();
        }
    };
    private static final Step VERIFYING_SIGS = new Step("Verifying a transaction's signatures.");
    private static final Step FINALISATION = new Step("Finalising a transaction.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.tracker();
        }
    };
    private final ProgressTracker progressTracker = new ProgressTracker(
            SENDING_AND_RECEIVING_DATA,
            EXTRACTING_VAULT_STATES,
            OTHER_TX_COMPONENTS,
            TX_BUILDING,
            TX_SIGNING,
            TX_VERIFICATION,
            SIGS_GATHERING,
            VERIFYING_SIGS,
            FINALISATION
    );
    private Party otherParty;
    private Party regulator;
    private Commodity amount;
    private CordaX500Name notaryName;
    private FlowSession counterpartySession;
    private StateAndRef inputStateAndRef;
    private StateAndContract outputStateAndContract;
    private Command<CommodityContract.Commands.Move> moveAssetCommand;
    private TimeWindow txnTimeWindow;
    private TransactionBuilder txBuilder;
    private SignedTransaction onceSignedTx;
    private SignedTransaction fullySignedTx;
    private SignedTransaction notarisedTx;

    public SellerFlow(Party otherParty, Commodity amount) {
        this.otherParty = otherParty;
        this.amount = amount;
    }

    @Override
    public SignedTransaction call() throws FlowException {
        PublicKey dummyPubKey = generateKeyPair().getPublic();

        //STAGE-1 - IDENTIFY_OTHER_NODES
        identifyOtherNodes();

        //STAGE-2 - SENDING_AND_RECEIVING_DATA
        sendAndRecieveData();

        //STAGE-3 - EXTRACTING_VAULT_STATES
        extractVaultStates();

        //STAGE-4 - OTHER_TX_COMPONENTS
        generateOtherTxnComponents();

        //STAGE-5 -  TX_BUILDING
        buildTransaction();

        //STAGE-6 - TX_SIGNING
        signTransaction();

        try {

            //STAGE-7 - TX_INITIATION
            verifyTransaction();

            //STAGE-8 - SIGS_GATHERING
            gatherSignatures();

            //STAGE-9 - VERIFYING_SIGS
            verfiySignatures();

        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }

        //STAGE-10 - FINALISATION
        finalisingTransaction();

        return null;
    }

    private void identifyOtherNodes(){

        progressTracker.setCurrentStep(IDENTIFY_OTHER_NODES);

        //Retrieve notary from network map
        notaryName = new CordaX500Name(GOLD_BLOCK_NOTARY_SERVICE, GOLD_BLOCK_NOTARY_CITY, GOLD_BLOCK_NOTARY_COUNTRY);
        regulator = getServiceHub().getNetworkMapCache().getNotary(notaryName);

        //Retrieve counter party
        CordaX500Name counterPartyName = new CordaX500Name("SPN_GOLD", "MUMBAI", "INDIA");
        Party namedCounterparty = getServiceHub().getIdentityService().wellKnownPartyFromX500Name(counterPartyName);

    }

    private void sendAndRecieveData() throws FlowException {
        progressTracker.setCurrentStep(SENDING_AND_RECEIVING_DATA);

        //Initiating a flow session with counterparty. This session will be used to send and receive messages from counterparty.
        counterpartySession = initiateFlow(otherParty);

        UntrustworthyData<Boolean> packet2 = counterpartySession.sendAndReceive(Boolean.class, "You can send and receive any class!");
        Boolean bool = packet2.unwrap(data -> {
            // Perform checking on the object received.
            // T O D O: Check the received object.
            // Return the object.
            return data;
        });

        FlowSession regulatorSession = initiateFlow(regulator);
        UntrustworthyData<Boolean> packet3 = regulatorSession.sendAndReceive(Boolean.class, "You can send and receive any class!");
        Boolean bool2 = packet2.unwrap(data -> {
            // Perform checking on the object received.
            // T O D O: Check the received object.
            // Return the object.
            return data;
        });
    }

    private void extractVaultStates() throws TransactionResolutionException {
        progressTracker.setCurrentStep(EXTRACTING_VAULT_STATES);

        VaultQueryCriteria criteria = new VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        Vault.Page<CommodityContract.State> results = getServiceHub().getVaultService().queryBy(CommodityContract.State.class, criteria);
        List<StateAndRef<CommodityContract.State>> dummyStates = results.getStates();
        StateRef inputStateRef = new StateRef(SecureHash.sha256("DummyTransactionHash"), 0);
        inputStateAndRef = getServiceHub().toStateAndRef(inputStateRef);
    }

    private void generateOtherTxnComponents(){
        progressTracker.setCurrentStep(OTHER_TX_COMPONENTS);
        final OpaqueBytes ref = OpaqueBytes.of((byte) 0x01);
        PartyAndReference partyAndReference = new PartyAndReference(otherParty, ref);

        CommodityContract.State outputState = new CommodityContract.State(partyAndReference, new Amount<Commodity>(100, amount.Companion.getInstance(GOLD_BLOCK_UNIQUE_CODE)), getServiceHub().getMyInfo().getLegalIdentities().get(0));
        outputStateAndContract = new StateAndContract(outputState, CommodityContract.Companion.toString());

        /*To be valid, the transaction requires a signature
         matching every public key in all of the transaction's commands.*/
        CommodityContract.Commands.Move commandData = new CommodityContract.Commands.Move();
        PublicKey ourPubKey = getServiceHub().getMyInfo().getLegalIdentitiesAndCerts().get(0).getOwningKey();
        PublicKey counterpartyPubKey = otherParty.getOwningKey();
        List<PublicKey> requiredSigners = ImmutableList.of(ourPubKey, counterpartyPubKey);
        moveAssetCommand = new Command<>(commandData, requiredSigners);

        /*Time windows represent the period of time during which a
          transaction must be notarised. They can have a start and an end
          time, or be open at either end.*/
         txnTimeWindow = TimeWindow.fromStartAndDuration(getServiceHub().getClock().instant(), Duration.ofSeconds(30));
    }

    private void buildTransaction() throws TransactionResolutionException, TransactionVerificationException, AttachmentResolutionException {
        progressTracker.setCurrentStep(TX_BUILDING);

        // If transaction has input states or a time-window, instantiate it with a notary.
        txBuilder = new TransactionBuilder(regulator);

        // Add items to the transaction builder
        txBuilder.withItems(
                // Inputs, as StateAndRef that reference to the outputs of previous transactions
                inputStateAndRef,
                // Outputs, as StateAndContract
                outputStateAndContract,
                // Command
                moveAssetCommand,
                // Attachments, as SecureHash
                null,
                // A txn time-window, as TimeWindow
                txnTimeWindow
        );

    }

    private void signTransaction(){
        progressTracker.setCurrentStep(TX_SIGNING);

        // Finalise the transaction by signing it & converting it into a SignedTransaction
        onceSignedTx = getServiceHub().signInitialTransaction(txBuilder);
    }

    private void verifyTransaction() throws FlowException, SignatureException {
        progressTracker.setCurrentStep(TX_VERIFICATION);

         /*Verifying a transaction will also verify every transaction in the transaction's dependency chain, which will require
         transaction data access on counterparty's node. The ``SendTransactionFlow`` can be used to automate the sending and
         data vending process. The ``SendTransactionFlow`` will listen for data request until the transaction is resolved and verified
         on the other side*/
        subFlow(new SendTransactionFlow(counterpartySession, onceSignedTx));

        onceSignedTx.verify(getServiceHub());

        /* To perform additional verification convert ``SignedTransaction`` into a ``LedgerTransaction``. This will use our ServiceHub
         to resolve the transaction's inputs and attachments into actual objects, rather than just references. */
//        LedgerTransaction ledgerTx = onceSignedTx.toLedgerTransaction(getServiceHub());
//
//        CommodityContract.State outputState = ledgerTx.outputsOfType(CommodityContract.State.class).get(0);
//        if (!outputState.getAmount().equals("new data")) {
//            // ``FlowException`` is a special exception type. It will be
//            // propagated back to any counterparty flows waiting for a
//            // message from this flow, notifying them that the flow has
//            // failed.
//            throw new FlowException("We expected different data.");
//        }

    }

    private void gatherSignatures() throws FlowException {
        progressTracker.setCurrentStep(SIGS_GATHERING);
         /*The list of parties who need to sign a transaction is dictated by the transaction's commands. Once we've signed a transaction
         ourselves, we can automatically gather the signatures of the other required signers using ``CollectSignaturesFlow``.
         The responder flow will need to call ``SignTransactionFlow``*/
        fullySignedTx = subFlow(new CollectSignaturesFlow(onceSignedTx, Collections.emptySet(), SIGS_GATHERING.childProgressTracker()));

    }

    private void verfiySignatures() throws SignatureException {
        progressTracker.setCurrentStep(VERIFYING_SIGS);
        // Verify that a transaction has all the required signatures, and that they're all valid.
        fullySignedTx.verifyRequiredSignatures();
    }

    private void finalisingTransaction() throws FlowException {
        progressTracker.setCurrentStep(FINALISATION);

        // Notarise the transaction and get it recorded in the vault of the participants of all the transaction's states.
        notarisedTx = subFlow(new FinalityFlow(fullySignedTx, FINALISATION.childProgressTracker()));

    }

}
