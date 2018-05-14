package com.template.ledger.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.template.ledger.common.constants.GoldBlockConstants;
import com.template.ledger.flow.BlockIssueFlow;
import com.template.ledger.flow.SellerFlow;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowProgressHandle;
import net.corda.core.node.NodeInfo;
import net.corda.core.transactions.SignedTransaction;
import net.corda.finance.contracts.Commodity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CREATED;

// This API is accessible from /api/template. The endpoint paths specified below are relative to it.
@Path("gb")
public class GoldBlockAPI implements GoldBlockConstants {

    static private final Logger logger = LoggerFactory.getLogger(GoldBlockAPI.class);

    private final CordaRPCOps rpcOps;
    private final CordaX500Name nodeName;
    private final List<String> serviceNames = ImmutableList.of("Controller", "Network Map Service");

    public GoldBlockAPI(CordaRPCOps services) {
        this.rpcOps = services;
        this.nodeName = rpcOps.nodeInfo().getLegalIdentities().get(0).getName();
    }


    /**
     * Returns the node's name.
     */
    @GET
    @Path("name")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, CordaX500Name> nodeDetails() {
        return ImmutableMap.of("name", nodeName);
    }

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<CordaX500Name>> getPeers() {
        List<NodeInfo> nodeInfoSnapshot = rpcOps.networkMapSnapshot();
        return ImmutableMap.of("peers", nodeInfoSnapshot
                .stream()
                .map(node -> node.getLegalIdentities().get(0).getName())
                .filter(name -> !name.equals(nodeName) && !serviceNames.contains(name.getOrganisation()))
                .collect(toList()));
    }

    @PUT
    @Path("issue")
    public Response issueGb(@QueryParam("orderValue") int orderValue, @QueryParam("partyName") CordaX500Name partyName) throws InterruptedException, ExecutionException {
        if (orderValue <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'tokenValue' must be non-negative.\n").build();
        }
        if (partyName == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'partyName' missing or has wrong format.\n").build();
        }

        final Party otherParty = rpcOps.wellKnownPartyFromX500Name(partyName);
        if (otherParty == null) {
            return Response.status(BAD_REQUEST).entity("Party named " + partyName + "cannot be found.\n").build();
        }

        try {
            FlowProgressHandle<SignedTransaction> flowHandle = rpcOps
                    .startTrackedFlowDynamic(BlockIssueFlow.class, orderValue);
            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            final SignedTransaction result = flowHandle
                    .getReturnValue()
                    .get();


            final String msg = String.format("Transaction id %s committed to ledger.\n", result.getId());
            return Response.status(CREATED).entity(msg).build();

        } catch (Throwable ex) {
            final String msg = ex.getMessage();
            logger.error(ex.getMessage(), ex);
            return Response.status(BAD_REQUEST).entity(msg).build();
        }
    }

    @PUT
    @Path("move")
    public Response moveGb(@QueryParam("orderValue") int orderValue, @QueryParam("partyName") CordaX500Name partyName) throws InterruptedException, ExecutionException {
        if (orderValue <= 0) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'tokenValue' must be non-negative.\n").build();
        }
        if (partyName == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'partyName' missing or has wrong format.\n").build();
        }

        final Party otherParty = rpcOps.wellKnownPartyFromX500Name(partyName);
        if (otherParty == null) {
            return Response.status(BAD_REQUEST).entity("Party named " + partyName + "cannot be found.\n").build();
        }

        try {


            Commodity goldblocks = new Commodity(GOLD_BLOCK_UNIQUE_CODE, GOLD_BLOCK_DISPLAY_NAME, 2);

            FlowProgressHandle<SignedTransaction> flowHandle = rpcOps
                    .startTrackedFlowDynamic(SellerFlow.class, otherParty, goldblocks);
            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            final SignedTransaction result = flowHandle
                    .getReturnValue()
                    .get();


            final String msg = String.format("Transaction id %s committed to ledger.\n", result.getId());
            return Response.status(CREATED).entity(msg).build();

        } catch (Throwable ex) {
            final String msg = ex.getMessage();
            logger.error(ex.getMessage(), ex);
            return Response.status(BAD_REQUEST).entity(msg).build();
        }
    }
}
